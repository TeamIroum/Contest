package kr.iroum.inspection.patrol.command;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import kr.iroum.inspection.common.audit.AuditLog;

/**
 * 사진 전송 기한 뒤 건너뛰기 확정(03단계 설계안 5.5 사례 7b, D-17·D-27·D-38).
 * 조 종료·중도 복귀로 자동 미방문(NOT_VISITED, TEAM_ENDED·AUTO_ABORT)이 된 장소 중 건너뛰기 이력이 있는 장소는, 전송 기한(sync_deadline_at)이 지나
 * 더 이상 적법한 늦은 사진이 올 수 없으면 SKIPPED로 확정한다. 열린 구도는 SKIPPED로 두고 기준 건너뛰기(발생 시각이 가장 이른 이력)를 연결한다.
 * 채택·검토 인계된 구도, 사진 처리 중인 장소, 건너뛰기 이력이 없는 자동 미방문은 바꾸지 않는다. 활동 상태·종료 기록은 그대로다.
 * 변경은 감사 기록(STOP_SKIP_CONFIRMED, 시스템)으로 남긴다. 잠금: 순회(6) → 장소(9) → 구도(10).
 */
@Component
public class DeadlineSkipConfirmation {

    private static final Logger log = LoggerFactory.getLogger(DeadlineSkipConfirmation.class);

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public DeadlineSkipConfirmation(JdbcTemplate jdbc, AuditLog audit, Clock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(initialDelayString = "${inspection.activity.skip-confirm-interval:5m}", fixedDelayString = "${inspection.activity.skip-confirm-interval:5m}")
    public void scheduled() {
        try {
            confirmDue();
        } catch (RuntimeException e) {
            log.warn("기한 뒤 건너뛰기 확정 실패(다음 주기 재시도): {}", e.getClass().getSimpleName());
        }
    }

    /** 확정한 장소 수. */
    public int confirmDue() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT ps.id AS stop_id, ps.patrol_id FROM patrol_stops ps JOIN patrols p ON p.id = ps.patrol_id
                WHERE ps.visit_status = 'NOT_VISITED' AND ps.not_visited_reason IS NOT NULL AND p.sync_deadline_at <= ?
                  AND EXISTS (SELECT 1 FROM patrol_events e WHERE e.patrol_stop_id = ps.id AND e.type = 'STOP_SKIPPED')
                ORDER BY ps.patrol_id, ps.id LIMIT 200""", Timestamp.from(clock.instant()));
        int done = 0;
        for (Map<String, Object> row : due) {
            Boolean changed = tx.execute(s -> confirm((UUID) row.get("patrol_id"), (UUID) row.get("stop_id")));
            if (Boolean.TRUE.equals(changed)) {
                done++;
            }
        }
        return done;
    }

    private boolean confirm(UUID patrolId, UUID stopId) {
        jdbc.queryForList("SELECT id FROM patrols WHERE id = ? FOR UPDATE", patrolId);
        Map<String, Object> stop = jdbc.queryForMap("""
                SELECT ps.visit_status, ps.not_visited_reason, ps.program_id, p.sync_deadline_at FROM patrol_stops ps JOIN patrols p ON p.id = ps.patrol_id
                WHERE ps.id = ? FOR UPDATE OF ps""", stopId);
        Instant now = clock.instant();
        if (!"NOT_VISITED".equals(stop.get("visit_status")) || stop.get("not_visited_reason") == null
                || ((Timestamp) stop.get("sync_deadline_at")).toInstant().isAfter(now)) {
            return false;
        }
        List<Map<String, Object>> slots = jdbc.queryForList("""
                SELECT id, resolution, active_inspection_id, required FROM capture_slots WHERE patrol_stop_id = ? ORDER BY id FOR UPDATE""", stopId);
        if (slots.stream().anyMatch(s -> s.get("active_inspection_id") != null)) {
            return false;
        }
        UUID skip = jdbc.queryForObject("""
                SELECT id FROM patrol_events WHERE patrol_stop_id = ? AND type = 'STOP_SKIPPED' ORDER BY occurred_at, received_at, id LIMIT 1""",
                UUID.class, stopId);
        jdbc.update("""
                UPDATE capture_slots SET resolution = 'SKIPPED', skipped_by_event_id = ?, version = version + 1, updated_at = ?
                WHERE patrol_stop_id = ? AND resolution = 'OPEN'""", skip, Timestamp.from(now), stopId);
        List<String> required = jdbc.queryForList("SELECT resolution FROM capture_slots WHERE patrol_stop_id = ? AND required", String.class,
                stopId);
        String next = !required.isEmpty() && required.stream().allMatch("ACCEPTED"::equals) ? "CAPTURED"
                : required.contains("SKIPPED") || required.isEmpty() ? "SKIPPED" : "REVIEW_REQUESTED";
        jdbc.update("UPDATE patrol_stops SET visit_status = ?, not_visited_reason = NULL WHERE id = ?", next, stopId);
        jdbc.update("UPDATE patrols SET revision = revision + 1, updated_at = now() WHERE id = ?", patrolId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("visitStatus", "NOT_VISITED");
        before.put("notVisitedReason", stop.get("not_visited_reason"));
        audit.record((UUID) stop.get("program_id"), null, "STOP_SKIP_CONFIRMED", "PATROL_STOP", stopId, "SYNC_DEADLINE_PASSED", before,
                Map.of("visitStatus", next, "skipEventId", skip));
        return true;
    }
}
