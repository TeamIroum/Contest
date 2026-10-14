package kr.iroum.inspection.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

/** 출발 전 재배정(ACT-05): 출발 거점이 다른 코스로 바꾸면서 조원도 함께 바꾸는 경우 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class PatrolReassignmentIntegrationTest {

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
    void routeWithAnotherBaseAndMemberSwapInOneRequest() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        UUID kim = p.participants().get(0);
        // 다른 집결 거점에서 출발하는 코스
        UUID otherBase = f.org("MEETING_POINT", f.agency, "다른 경로당");
        UUID route = f.id("INSERT INTO routes (program_id, organization_id, name) VALUES (?, ?, '다른 코스') RETURNING id", f.program, f.agency);
        UUID otherVersion = f.id("""
                INSERT INTO route_versions (program_id, route_id, version_no, departure_base_id, return_base_id, published_at)
                VALUES (?, ?, 1, ?, ?, now()) RETURNING id""", f.program, route, otherBase, otherBase);
        // 박○○ 대신 넣을 이○○
        UUID leeUser = f.user("이○○");
        f.role(leeUser, "SENIOR", f.agency);
        UUID lee = f.id("INSERT INTO participants (program_id, user_id, organization_id) VALUES (?, ?, ?) RETURNING id", f.program, leeUser,
                f.agency);
        long version = jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, p.id());

        api.post(f.manager, "/api/v1/admin/patrols/" + p.id() + "/reassignments", """
                {"participantIds": ["%s", "%s"], "routeVersionId": "%s", "reason": "결원 교체와 코스 변경", "version": %d}"""
                .formatted(kim, lee, otherVersion, version)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT base_organization_id FROM patrols WHERE id = ?", UUID.class, p.id())).isEqualTo(otherBase);
        assertThat(jdbc.queryForObject("SELECT route_version_id FROM patrols WHERE id = ?", UUID.class, p.id())).isEqualTo(otherVersion);
        List<UUID> members = jdbc.queryForList(
                "SELECT participant_id FROM patrol_members WHERE patrol_id = ? AND assignment_active ORDER BY seat_no", UUID.class, p.id());
        assertThat(members).containsExactlyInAnyOrder(kim, lee);
    }
}
