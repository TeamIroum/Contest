package kr.iroum.inspection.patrol;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.MutableClock;
import kr.iroum.inspection.support.PatrolFixture;

/** 순회 조회(업무 API 6.2·8절): 본인 배정·관할 범위, 계약 형식, 시니어 비노출 필드. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class PatrolReadIntegrationTest {

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
    PatrolFixture.Patrol patrol;
    String managerPhone;
    String seniorPhone;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockConfiguration.NOW);
        f = new PatrolFixture(jdbc, sessions, phones);
        patrol = f.patrol(2);
        managerPhone = f.phone(f.manager);
        seniorPhone = f.phone(patrol.users().get(0));
    }

    @Test
    void seniorSeesOwnAssignmentForTheDayAndNothingElse() throws Exception {
        String body = call(get("/api/v1/me/patrols?date=2026-10-15"), patrol.users().get(0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(patrol.id().toString()))
                .andExpect(jsonPath("$.items[0].memberCount").value(2))
                .andExpect(jsonPath("$.items[0].myAttendance").value("UNCHECKED"))
                .andExpect(jsonPath("$.items[0].progress.total").value(3))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn().getResponse().getContentAsString();
        assertMatches("TodayPatrols", body);
        call(get("/api/v1/me/patrols?date=2026-10-16"), patrol.users().get(0)).andExpect(jsonPath("$.items.length()").value(0));
        UUID stranger = strangerSenior();
        call(get("/api/v1/me/patrols?date=2026-10-15"), stranger).andExpect(jsonPath("$.items.length()").value(0));
        call(get("/api/v1/me/patrols"), patrol.users().get(0)).andExpect(status().isBadRequest());
        call(get("/api/v1/me/patrols?date=2026-10-15&size=0"), patrol.users().get(0)).andExpect(status().isBadRequest());
    }

    @Test
    void todayListUsesTheSameActiveConditionAsDetail() throws Exception {
        UUID kim = patrol.users().get(0);
        // 참여자 중지: 상세가 404이면 목록에도 없다.
        jdbc.update("UPDATE participants SET status = 'SUSPENDED' WHERE id = ?", patrol.participants().get(0));
        call(get("/api/v1/patrols/" + patrol.id()), kim).andExpect(status().isNotFound());
        call(get("/api/v1/me/patrols?date=2026-10-15"), kim).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
        jdbc.update("UPDATE participants SET status = 'ACTIVE' WHERE id = ?", patrol.participants().get(0));
        call(get("/api/v1/me/patrols?date=2026-10-15"), kim).andExpect(jsonPath("$.items.length()").value(1));
        // 이 사업의 SENIOR 역할이 회수되면 상세·목록 모두 보이지 않는다(다른 사업 역할이 있어 로그인은 유지).
        UUID otherProgram = jdbc.queryForObject("INSERT INTO programs (name) VALUES ('다른 사업') RETURNING id", UUID.class);
        UUID otherOrg = jdbc.queryForObject("INSERT INTO organizations (program_id, type, name) VALUES (?, 'AGENCY', '다른 기관') RETURNING id",
                UUID.class, otherProgram);
        jdbc.update("INSERT INTO user_role_scopes (program_id, user_id, organization_id, role) VALUES (?, ?, ?, 'SENIOR')", otherProgram, kim, otherOrg);
        jdbc.update("UPDATE user_role_scopes SET revoked_at = now() WHERE user_id = ? AND program_id = ?", kim, f.program);
        call(get("/api/v1/patrols/" + patrol.id()), kim).andExpect(status().isNotFound());
        call(get("/api/v1/me/patrols?date=2026-10-15"), kim).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void patrolDetailSyncStateAndOfflinePackageFollowContract() throws Exception {
        UUID senior = patrol.users().get(0);
        String detail = call(get("/api/v1/patrols/" + patrol.id()), senior).andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.currentStopId").value(patrol.stops().get(0).toString()))
                .andExpect(jsonPath("$.stops[1].captureSlots.length()").value(2))
                .andExpect(jsonPath("$.stops[0].captureSlots[0].receiptStatus").value("NONE"))
                .andExpect(jsonPath("$.stops[0].captureSlots[0].retakesRemaining").value(2))
                .andExpect(jsonPath("$.contact.name").value("최○○"))
                .andExpect(jsonPath("$.contact.phoneNumber").value(managerPhone))
                .andExpect(jsonPath("$.syncAllowed").value(true))
                .andExpect(jsonPath("$.syncDeadlineAt").value("2026-10-15T15:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("Patrol", detail);
        String sync = call(get("/api/v1/patrols/" + patrol.id() + "/sync-state"), senior).andExpect(status().isOk())
                .andExpect(jsonPath("$.patrolId").value(patrol.id().toString()))
                .andExpect(jsonPath("$.members[0].name").doesNotExist())
                .andExpect(jsonPath("$.assets.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        assertMatches("SyncState", sync);
        String offline = call(get("/api/v1/patrols/" + patrol.id() + "/offline-package"), senior).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertMatches("OfflinePackage", offline);

        // 기한(활동일 다음 날 00:00 KST)이 지나면 전송을 멈추게 한다.
        clock.set(java.time.Instant.parse("2026-10-15T15:00:00Z"));
        call(get("/api/v1/patrols/" + patrol.id() + "/sync-state"), senior).andExpect(jsonPath("$.syncAllowed").value(false));
    }

    @Test
    void seniorCannotReadOtherPatrolsAndManagerCannotUseSeniorPaths() throws Exception {
        call(get("/api/v1/patrols/" + patrol.id()), strangerSenior()).andExpect(status().isNotFound());
        call(get("/api/v1/patrols/" + UUID.randomUUID() + "/sync-state"), patrol.users().get(0)).andExpect(status().isNotFound());
        call(get("/api/v1/patrols/" + patrol.id()), f.manager).andExpect(status().isForbidden());
        call(get("/api/v1/patrols/not-a-uuid"), patrol.users().get(0)).andExpect(status().isBadRequest());
    }

    @Test
    void managerSeesScopedPatrolsWithAdminFields() throws Exception {
        String list = call(get("/api/v1/admin/patrols?date=2026-10-15"), f.manager).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].attendanceSummary.total").value(2))
                .andExpect(jsonPath("$.items[0].attendanceSummary.unchecked").value(2))
                .andExpect(jsonPath("$.items[0].startMissingCount").value(0))
                .andExpect(jsonPath("$.items[0].returnOverdue").value(false))
                .andReturn().getResponse().getContentAsString();
        assertMatches("AdminPatrols", list);
        call(get("/api/v1/admin/patrols?activityStatus=ENDED"), f.manager).andExpect(jsonPath("$.items.length()").value(0));
        call(get("/api/v1/admin/patrols?activityStatus=COMPLETED"), f.manager).andExpect(status().isBadRequest());
        String detail = call(get("/api/v1/admin/patrols/" + patrol.id()), f.manager).andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].phoneNumber").value(seniorPhone))
                .andExpect(jsonPath("$.members[1].phoneNumber").value(""))
                .andExpect(jsonPath("$.members[0].startMissing").value(false))
                .andExpect(jsonPath("$.contact").doesNotExist())
                .andExpect(jsonPath("$.recentEvents.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        assertMatches("AdminPatrol", detail);

        // 예정 복귀 시각이 지나면 표시용 지연 플래그.
        clock.set(PatrolFixture.END.plusSeconds(60));
        call(get("/api/v1/admin/patrols/" + patrol.id()), f.manager).andExpect(jsonPath("$.returnOverdue").value(true));

        // 다른 기관 담당자는 목록에 없고 상세는 404, 시니어는 403.
        UUID other = f.user("다른 담당자");
        f.role(other, "ACTIVITY_MANAGER", f.org("AGENCY", null, "다른 복지관"));
        call(get("/api/v1/admin/patrols"), other).andExpect(jsonPath("$.items.length()").value(0));
        call(get("/api/v1/admin/patrols/" + patrol.id()), other).andExpect(status().isNotFound());
        call(get("/api/v1/admin/patrols/" + patrol.id()), patrol.users().get(0)).andExpect(status().isForbidden());
    }

    private UUID strangerSenior() {
        UUID stranger = f.user("가상 시니어");
        f.role(stranger, "SENIOR", f.agency);
        jdbc.update("INSERT INTO participants (program_id, user_id, organization_id) VALUES (?, ?, ?)", f.program, stranger, f.agency);
        return stranger;
    }

    private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, UUID user)
            throws Exception {
        return mvc.perform(request.header("Authorization", f.token(user)));
    }
}
