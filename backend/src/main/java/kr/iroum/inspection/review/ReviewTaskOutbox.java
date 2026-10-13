package kr.iroum.inspection.review;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사진 결과가 남긴 검토 요청(outbox REVIEW_TASK_REQUESTED)을 검토 작업으로 만든다(업무 API 9절, DB 설계 8·12절, 04단계 설계 S6).
 * <ul>
 * <li>사진 결과 커밋과 같은 트랜잭션에 outbox 행만 남기고, 작업 생성은 커밋 뒤 별도 트랜잭션에서 한다. 순회 워커가 inspection을 잠근 채
 * review_tasks를 잠그지 않게 하기 위해서다.</li>
 * <li>같은 원인은 trigger_key UNIQUE로 한 번만 만든다(재전달·재실행 멱등). 실패하면 outbox를 RETRY로 두고 주기 작업이 다시 처리한다.</li>
 * <li>검토 작업에 우선순위·등급을 두지 않는다(D-10·D-13). 접수순으로 본다.</li>
 * </ul>
 */
@Component
public class ReviewTaskOutbox {

    public static final String EVENT = "REVIEW_TASK_REQUESTED";
    private static final Logger log = LoggerFactory.getLogger(ReviewTaskOutbox.class);

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ReviewTaskOutbox(JdbcTemplate jdbc, JsonMapper json, Clock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 사진 결과 트랜잭션 안에서 부른다. */
    public void enqueue(UUID programId, UUID inspectionId, UUID stopId, UUID slotId, String triggerReason) {
        Map<String, Object> payload = Map.of("programId", programId, "inspectionId", inspectionId, "stopId", stopId, "slotId", slotId,
                "triggerReason", triggerReason);
        jdbc.update("INSERT INTO outbox_events (event_type, aggregate_id, payload_json, next_run_at) VALUES (?, ?, ?::jsonb, ?)", EVENT,
                inspectionId, json.writeValueAsString(payload), Timestamp.from(clock.instant()));
    }

    /** 처리할 수 있는 요청을 모두 처리한다. 실패한 요청은 다음 주기에 다시 처리한다. */
    @Scheduled(initialDelayString = "${inspection.review.outbox-interval:30s}", fixedDelayString = "${inspection.review.outbox-interval:30s}")
    public void drain() {
        for (int i = 0; i < 100; i++) {
            Optional<UUID> handled;
            try {
                handled = tx.execute(s -> processOne());
            } catch (RuntimeException e) {
                log.warn("검토 작업 생성 실패: {}", e.getClass().getSimpleName());
                markFailedOldest();
                return;
            }
            if (handled == null || handled.isEmpty()) {
                return;
            }
        }
    }

    private Optional<UUID> processOne() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, payload_json::text AS payload FROM outbox_events
                WHERE event_type = ? AND status IN ('PENDING', 'RETRY') AND next_run_at <= ?
                ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED""", EVENT, Timestamp.from(clock.instant()));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        UUID id = (UUID) rows.get(0).get("id");
        create(json.readTree((String) rows.get(0).get("payload")), id);
        jdbc.update("UPDATE outbox_events SET status = 'DONE', attempts = attempts + 1 WHERE id = ?", id);
        return Optional.of(id);
    }

    /** 잠금 순서: review_tasks(4)만 쓰고, 사진 연결 행의 FK 확인이 inspections(11)를 읽는다. 순회·구도는 잠그지 않는다. */
    private void create(JsonNode p, UUID outboxId) {
        UUID program = UUID.fromString(p.get("programId").asString());
        UUID inspection = UUID.fromString(p.get("inspectionId").asString());
        UUID stop = UUID.fromString(p.get("stopId").asString());
        UUID slot = UUID.fromString(p.get("slotId").asString());
        String reason = p.get("triggerReason").asString();
        boolean captureFailure = "QUALITY_RETRY_EXHAUSTED".equals(reason) || "TECHNICAL_FAILURE".equals(reason);
        // 품질 재촬영 소진은 구도 단위로 하나, 그 밖의 원인은 사진(시도) 단위로 하나.
        // 개인정보 보류는 같은 사진에 여러 번 생길 수 있어(공개 뒤 새 보류) 보류 요청마다 하나.
        String key = "QUALITY_RETRY_EXHAUSTED".equals(reason) ? reason + ":slot:" + slot
                : "PRIVACY_HOLD".equals(reason) ? reason + ":inspection:" + inspection + ":" + outboxId
                : reason + ":inspection:" + inspection;
        jdbc.update("""
                INSERT INTO review_tasks (program_id, facility_id, patrol_stop_id, type, trigger_reason, trigger_key, assigned_department_id,
                    created_at, updated_at)
                SELECT ps.program_id, ps.facility_id, ps.id, ?, ?, ?, f.managing_department_id, ?, ?
                FROM patrol_stops ps JOIN facilities f ON f.id = ps.facility_id WHERE ps.id = ?
                ON CONFLICT (program_id, trigger_key) DO NOTHING""",
                captureFailure ? "CAPTURE_FAILURE" : "INSPECTION", reason, key, Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()), stop);
        UUID task = jdbc.queryForObject("SELECT id FROM review_tasks WHERE program_id = ? AND trigger_key = ?", UUID.class, program, key);
        List<UUID> linked = "QUALITY_RETRY_EXHAUSTED".equals(reason)
                ? jdbc.queryForList("SELECT id FROM inspections WHERE capture_slot_id = ? AND retake_reason IS NOT NULL ORDER BY attempt_no",
                        UUID.class, slot)
                : List.of(inspection);
        for (UUID i : linked) {
            jdbc.update("""
                    INSERT INTO review_task_inspections (program_id, review_task_id, inspection_id) VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING""", program, task, i);
        }
    }

    private void markFailedOldest() {
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE outbox_events SET status = 'RETRY', attempts = attempts + 1,
                    next_run_at = CAST(? AS timestamptz) + make_interval(secs => LEAST(600, 30 * power(2, LEAST(attempts, 5))))
                WHERE id = (SELECT id FROM outbox_events WHERE event_type = ? AND status IN ('PENDING', 'RETRY') AND next_run_at <= ?
                    ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)""",
                Timestamp.from(now), EVENT, Timestamp.from(now)));
    }
}
