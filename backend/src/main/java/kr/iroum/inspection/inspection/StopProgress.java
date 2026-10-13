package kr.iroum.inspection.inspection;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 장소 진행 계산. 호출자가 순회·장소·구도를 잠근 트랜잭션 안에서 쓴다. */
@Component
public class StopProgress {

    private final JdbcTemplate jdbc;

    public StopProgress(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 장소 진행(업무 API 6.5절, D-27, D-39, 설계안 5.5). 처리 중 구도가 남아 있으면 그대로 둔다.
     * <ol>
     * <li>사진 처리 때문에 미뤄 둔 건너뛰기(skip_pending)는 처리가 모두 끝나면 남은 열린 구도에 적용한다. 채택·인계된 구도는 되돌리지 않는다.</li>
     * <li>필수 구도가 모두 끝났으면 모두 채택 → CAPTURED, 건너뛴 구도가 있으면 SKIPPED, 그 밖(인계 포함) → REVIEW_REQUESTED.</li>
     * <li>바꾸는 장소는 진행 중(PENDING), 자동 미방문(D-27), 건너뛰기(D-39로 받은 사진)뿐이다. 활동 상태·종료 기록은 바꾸지 않는다.</li>
     * </ol>
     */
    public void settle(UUID stopId) {
        settle(stopId, false);
    }

    /** fromCaptured: 개인정보 보류로 채택이 풀린 경우 CAPTURED 장소도 다시 계산한다(보류 → REVIEW_REQUESTED). */
    public void settle(UUID stopId, boolean fromCaptured) {
        Map<String, Object> stop = jdbc.queryForMap("SELECT visit_status, not_visited_reason, skip_pending_event_id FROM patrol_stops WHERE id = ?",
                stopId);
        Integer active = jdbc.queryForObject("SELECT count(*) FROM capture_slots WHERE patrol_stop_id = ? AND active_inspection_id IS NOT NULL",
                Integer.class, stopId);
        if (active != null && active > 0) {
            return;
        }
        UUID pending = (UUID) stop.get("skip_pending_event_id");
        if (pending != null) {
            jdbc.update("""
                    UPDATE capture_slots SET resolution = 'SKIPPED', skipped_by_event_id = ?, version = version + 1, updated_at = now()
                    WHERE patrol_stop_id = ? AND resolution = 'OPEN'""", pending, stopId);
            jdbc.update("UPDATE patrol_stops SET skip_pending_event_id = NULL WHERE id = ?", stopId);
        }
        String visit = (String) stop.get("visit_status");
        boolean changeable = "PENDING".equals(visit) || "SKIPPED".equals(visit) || (fromCaptured && "CAPTURED".equals(visit))
                || ("NOT_VISITED".equals(visit) && stop.get("not_visited_reason") != null);
        if (!changeable) {
            return;
        }
        List<String> required = jdbc.queryForList("SELECT resolution FROM capture_slots WHERE patrol_stop_id = ? AND required", String.class,
                stopId);
        if (required.isEmpty() || required.contains("OPEN")) {
            return;
        }
        String next = required.stream().allMatch("ACCEPTED"::equals) ? "CAPTURED"
                : required.contains("SKIPPED") ? "SKIPPED" : "REVIEW_REQUESTED";
        if (!next.equals(visit)) {
            jdbc.update("UPDATE patrol_stops SET visit_status = ?, not_visited_reason = NULL WHERE id = ?", next, stopId);
        }
    }
}
