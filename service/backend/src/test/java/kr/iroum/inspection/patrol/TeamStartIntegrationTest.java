package kr.iroum.inspection.patrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.support.ApiCaller;
import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.MutableClock;
import kr.iroum.inspection.support.PatrolFixture;

/**
 * service/: 기능정의서 출발 규칙(SEN-13·ACT-07·ACC-02, inspection.activity.team-start=true).
 * 출석 확정 2명 미만이면 출발 확인 거절, 출발 확인 전 시작 거절, 출석 확정 전 시작 거절, 한 사람이 누르면 출석한 조원 전원이 같은 시각으로 시작.
 * 원본 개인 시작(D-02) 시험은 StartDepartureIntegrationTest(기본값 false)가 그대로 맡는다.
 */
@SpringBootTest(properties = "inspection.activity.team-start=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class TeamStartIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Autowired
    PhoneCrypto phones;

    @Autowired
    MutableClock clock;

    PatrolFixture f;
    ApiCaller api;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(FixedClockConfiguration.NOW);
        f = new PatrolFixture(jdbc, sessions, phones);
        api = new ApiCaller(mvc, f);
    }

    @Test
    void startWaitsForAttendanceAndDepartureThenStartsWholeTeam() throws Exception {
        PatrolFixture.Patrol p = f.patrol(3);
        // 출발 확인 전 시작은 거절
        api.post(p.users().get(0), start(p), event()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("DEPARTURE_NOT_CONFIRMED"));
        // 출석 확정 1명이면 출발 확인 거절
        present(p, 0);
        api.post(f.manager, departure(p), "{\"version\":" + version(p) + "}", UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("ATTENDANCE_SHORT"));
        // 2명 확정 → 출발 확인
        present(p, 1);
        api.post(f.manager, departure(p), "{\"version\":" + version(p) + "}", UUID.randomUUID()).andExpect(status().isOk());
        // 출석 확정 전인 세 번째 조원은 시작할 수 없다
        api.post(p.users().get(2), start(p), event()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("ATTENDANCE_NOT_CONFIRMED"));
        // 한 사람이 누르면 출석한 두 사람 모두 같은 시각으로 시작, 출석 전인 사람은 그대로
        api.post(p.users().get(0), start(p), event()).andExpect(status().isOk()).andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"));
        assertThat(jdbc.queryForList("SELECT started_at IS NOT NULL FROM patrol_members WHERE patrol_id = ? ORDER BY seat_no", Boolean.class, p.id()))
                .containsExactly(true, true, false);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT started_at) FROM patrol_members WHERE patrol_id = ? AND started_at IS NOT NULL",
                Integer.class, p.id())).isEqualTo(1);
    }

    @Test
    void departureIsRefusedWhenAPresentMemberWasSuspended() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        present(p, 0);
        present(p, 1);
        // 출석 뒤 참여 중지(ACC-02): 출발 확인 거절, 상태를 되돌리면 다시 된다
        jdbc.update("UPDATE participants SET status = 'SUSPENDED' WHERE id = ?", p.participants().get(1));
        api.post(f.manager, departure(p), "{\"version\":" + version(p) + "}", UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("PARTICIPANT_SUSPENDED"));
        jdbc.update("UPDATE participants SET status = 'ACTIVE' WHERE id = ?", p.participants().get(1));
        api.post(f.manager, departure(p), "{\"version\":" + version(p) + "}", UUID.randomUUID()).andExpect(status().isOk());
    }

    private void present(PatrolFixture.Patrol p, int index) throws Exception {
        api.put(f.manager, "/api/v1/admin/patrols/" + p.id() + "/attendance/" + p.participants().get(index),
                "{\"attendance\":\"PRESENT\",\"proxyReason\":\"시험: 센터에서 직접 확인\",\"version\":" + version(p) + "}").andExpect(status().isOk());
    }

    private long version(PatrolFixture.Patrol p) {
        return jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, p.id());
    }

    private static String start(PatrolFixture.Patrol p) {
        return "/api/v1/patrols/" + p.id() + "/activity-starts";
    }

    private static String departure(PatrolFixture.Patrol p) {
        return "/api/v1/admin/patrols/" + p.id() + "/departure-confirmations";
    }

    private static String event() {
        return "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:05:00Z\"}";
    }
}
