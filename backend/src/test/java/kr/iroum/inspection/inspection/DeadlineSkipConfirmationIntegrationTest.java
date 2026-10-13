package kr.iroum.inspection.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.iroum.inspection.patrol.command.DeadlineSkipConfirmation;
import kr.iroum.inspection.support.MockAiServer;

/** 사진 전송 기한 뒤 자동 미방문 장소의 건너뛰기 확정(설계안 5.5 사례 7b, PHOTO-08). */
class DeadlineSkipConfirmationIntegrationTest extends PhotoTestBase {

    @Autowired
    DeadlineSkipConfirmation confirmation;

    @Test
    void skipReportedAfterEndBecomesSkippedOnlyAfterSyncDeadline() throws Exception {
        endPatrol();
        // 종료 뒤 도착한 건너뛰기(복귀 전 발생)는 이력만 남고 자동 미방문은 그대로다.
        skip(1, 1, "2026-10-15T01:10:00Z").andExpect(status().isOk()).andExpect(jsonPath("$.stopUpdated").value(false));
        skip(1, 2, "2026-10-15T01:12:00Z").andExpect(status().isOk());
        skip(0, 2, "2026-10-15T01:11:00Z").andExpect(status().isOk());
        // 장소 ②의 구도 1은 늦은 사진으로 채택(D-27), 구도 2는 열린 채 미방문.
        submit(0, 1, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:05:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk());
        assertThat(stops()).isEqualTo("NOT_VISITED,NOT_VISITED,NOT_VISITED");
        long revision = jdbc.queryForObject("SELECT revision FROM patrols WHERE id = ?", Long.class, p.id());

        assertThat(confirmation.confirmDue()).isZero(); // 기한 전에는 늦은 사진을 기다린다.
        clock.set(Instant.parse("2026-10-15T15:00:00Z"));
        assertThat(confirmation.confirmDue()).isEqualTo(2);
        // 건너뛰기 이력이 없는 ①은 그대로, ②는 채택 구도 유지·나머지 SKIPPED, ③은 SKIPPED. 기준 건너뛰기는 발생 시각이 가장 이른 것.
        assertThat(stops()).isEqualTo("NOT_VISITED,SKIPPED,SKIPPED");
        assertThat(jdbc.queryForList("SELECT resolution FROM capture_slots WHERE patrol_stop_id = ? ORDER BY sequence_no", String.class,
                p.stops().get(1))).containsExactly("ACCEPTED", "SKIPPED");
        assertThat(jdbc.queryForObject("""
                SELECT e.occurred_at FROM capture_slots s JOIN patrol_events e ON e.id = s.skipped_by_event_id WHERE s.id = ?""",
                java.time.OffsetDateTime.class, p.slots().get(3)).toInstant()).isEqualTo(Instant.parse("2026-10-15T01:11:00Z"));
        assertThat(jdbc.queryForObject("SELECT activity_status FROM patrols WHERE id = ?", String.class, p.id())).isEqualTo("ENDED");
        assertThat(jdbc.queryForObject("SELECT revision FROM patrols WHERE id = ?", Long.class, p.id())).isGreaterThan(revision);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE action = 'STOP_SKIP_CONFIRMED' AND resource_id IN (?, ?) AND actor_user_id IS NULL""",
                Integer.class, p.stops().get(1), p.stops().get(2))).isEqualTo(2);
        assertThat(confirmation.confirmDue()).isZero();
    }

    private String stops() {
        return String.join(",", jdbc.queryForList("SELECT visit_status FROM patrol_stops WHERE patrol_id = ? ORDER BY sequence_no", String.class,
                p.id()));
    }
}
