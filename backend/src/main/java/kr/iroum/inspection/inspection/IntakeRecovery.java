package kr.iroum.inspection.inspection;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import kr.iroum.inspection.analysis.AnalysisSnapshots;
import kr.iroum.inspection.analysis.AnalyzeOutcome;
import kr.iroum.inspection.analysis.client.AiClient;
import kr.iroum.inspection.analysis.client.AiClient.AiException;
import tools.jackson.databind.JsonNode;

/**
 * 사진 접수의 주기 복구(DB 설계 7.1·11절, 04단계 공백 G5).
 * <ol>
 * <li>접수 리스 만료: 수신 중(RECEIVING)인데 리스가 끝난 시도를 {@link PhotoIntakeService#recoverExpiredLease}로 정리한다.
 * 같은 AI 호출 ID의 저장 결과가 있으면 반영하고, 없으면 RETRY_REQUIRED(기기 재전송)로 둔다.</li>
 * <li>AI 재판독 추적 기한: AI가 retry.pending으로 넘긴 작업이 기한(inspection.ai.reanalysis-deadline)까지 끝나지 않으면 마지막으로
 * GET /results를 한 번 확인해 새 결과면 스냅샷만 추가하고, 그래도 끝나지 않았으면 추적만 끝낸다(FAILED, REANALYSIS_DEADLINE).
 * 검토 작업은 needs_review 접수 때 이미 만들었으므로 새로 만들지 않는다. 사람 판정·구도·활동은 바꾸지 않는다.</li>
 * </ol>
 * 외부 AI 호출 동안 DB 잠금을 잡지 않는다. 각 대상은 독립적으로 처리하며 실패하면 다음 주기에 다시 시도한다.
 */
@Component
public class IntakeRecovery {

    private static final Logger log = LoggerFactory.getLogger(IntakeRecovery.class);

    private final JdbcTemplate jdbc;
    private final PhotoIntakeService intake;
    private final AiClient ai;
    private final AnalysisSnapshots snapshots;
    private final Clock clock;
    private final TransactionTemplate tx;

    public IntakeRecovery(JdbcTemplate jdbc, PhotoIntakeService intake, AiClient ai, AnalysisSnapshots snapshots, Clock clock,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.intake = intake;
        this.ai = ai;
        this.snapshots = snapshots;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(initialDelayString = "${inspection.photo.recovery-interval:30s}", fixedDelayString = "${inspection.photo.recovery-interval:30s}")
    public void run() {
        recoverExpiredLeases();
        closeExpiredAiTracking();
    }

    /** 처리한 시도 수. */
    public int recoverExpiredLeases() {
        List<UUID> due = jdbc.queryForList("""
                SELECT id FROM inspections WHERE ingestion_lease_until <= ?
                    AND (receipt_status = 'RECEIVING' OR (receipt_status = 'RECEIVED' AND capture_status = 'PROCESSING'))
                ORDER BY ingestion_lease_until LIMIT 50""", UUID.class, Timestamp.from(clock.instant()));
        int done = 0;
        for (UUID id : due) {
            try {
                if (intake.recoverExpiredLease(id)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.warn("접수 리스 복구 실패(다음 주기 재시도): {}", e.getClass().getSimpleName());
            }
        }
        return done;
    }

    /** 끝낸 추적 수. */
    public int closeExpiredAiTracking() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id, program_id, provider_photo_id FROM analysis_jobs
                WHERE retry_owner = 'AI' AND status = 'PROCESSING' AND next_run_at <= ? ORDER BY next_run_at LIMIT 50""",
                Timestamp.from(clock.instant()));
        int done = 0;
        for (Map<String, Object> job : due) {
            UUID jobId = (UUID) job.get("id");
            Optional<JsonNode> latest;
            try {
                latest = ai.result((String) job.get("provider_photo_id"));
            } catch (AiException e) {
                latest = Optional.empty();
            }
            Optional<JsonNode> result = latest;
            try {
                Boolean closed = tx.execute(s -> close(jobId, (UUID) job.get("program_id"), result));
                if (Boolean.TRUE.equals(closed)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.warn("재판독 추적 종료 실패(다음 주기 재시도): {}", e.getClass().getSimpleName());
            }
        }
        return done;
    }

    private boolean close(UUID jobId, UUID programId, Optional<JsonNode> result) {
        Instant now = clock.instant();
        Boolean stillDue = jdbc.queryForObject("""
                SELECT retry_owner = 'AI' AND status = 'PROCESSING' AND next_run_at <= ? FROM analysis_jobs WHERE id = ? FOR UPDATE""",
                Boolean.class, Timestamp.from(now), jobId);
        if (!Boolean.TRUE.equals(stillDue)) {
            return false;
        }
        boolean finished = result.isPresent() && !AnalyzeOutcome.retryPending(result.get());
        if (result.isPresent()) {
            UUID media = jdbc.query("""
                    SELECT i.approved_media_id FROM analysis_jobs j JOIN inspections i ON i.id = j.inspection_id WHERE j.id = ?""",
                    (rs, i) -> rs.getObject(1, UUID.class), jobId).stream().findFirst().orElse(null);
            snapshots.append(programId, jobId, result.get(), media, now);
        }
        jdbc.update("""
                UPDATE analysis_jobs SET status = ?, error_code = ?, next_run_at = NULL, finished_at = ?, updated_at = ? WHERE id = ?""",
                finished ? "SUCCEEDED" : "FAILED", finished ? null : "REANALYSIS_DEADLINE", Timestamp.from(now), Timestamp.from(now), jobId);
        return true;
    }
}
