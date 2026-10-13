package kr.iroum.inspection.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Consumer;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.account.AccountRepository;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/**
 * 역할·관할 검사. local seed(가상 시연 데이터) 위에서 확인하고 각 테스트는 롤백한다.
 * 역할 없음은 403, 역할은 있으나 범위 밖·없는 대상은 404(존재 숨김).
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@Transactional
class AccessPolicyTest {

    static final UUID PROGRAM = UUID.fromString("c9000000-0000-4000-8000-000000000001");
    static final UUID AGENCY = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final UUID MEETING_POINT = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    static final UUID ROAD_DEPT = UUID.fromString("a0000000-0000-4000-8000-000000000021");
    static final UUID SEWER_DEPT = UUID.fromString("a0000000-0000-4000-8000-000000000022");
    static final UUID KIM = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID CHOI = UUID.fromString("10000000-0000-4000-8000-000000000021");
    static final UUID LEE = UUID.fromString("10000000-0000-4000-8000-000000000011");
    static final UUID JEONG = UUID.fromString("10000000-0000-4000-8000-000000000012");
    static final UUID PATROL = UUID.fromString("30000000-0000-4000-8000-000000000001");
    static final UUID SIDEWALK = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    static final UUID DRAIN = UUID.fromString("f0000000-0000-4000-8000-000000000002");

    @Autowired
    AccessPolicy policy;

    @Autowired
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void facilityManagerSeesOnlyFacilitiesHisDepartmentCurrentlyManages() {
        assertThat(policy.requireFacility(user(LEE), SIDEWALK)).isEqualTo(PROGRAM);
        expect(ErrorCode.NOT_FOUND, () -> policy.requireFacility(user(LEE), DRAIN));
        expect(ErrorCode.NOT_FOUND, () -> policy.requireFacility(user(LEE), UUID.randomUUID()));
        assertThat(policy.requireFacility(user(JEONG), DRAIN)).isEqualTo(PROGRAM);

        // 시설 대장의 관리 부서가 바뀌면 다음 요청부터 새 부서 관할이다.
        jdbc.update("UPDATE facilities SET managing_department_id = ? WHERE id = ?", SEWER_DEPT, SIDEWALK);
        expect(ErrorCode.NOT_FOUND, () -> policy.requireFacility(user(LEE), SIDEWALK));
        assertThat(policy.requireFacility(user(JEONG), SIDEWALK)).isEqualTo(PROGRAM);
    }

    @Test
    void rolesDoNotImplyEachOther() {
        // 활동 담당자·시스템 관리자에게 시설 판정 권한이 없다(403). 시니어는 관리자 순회 접근 불가.
        expect(ErrorCode.FORBIDDEN, () -> policy.requireFacility(user(CHOI), SIDEWALK));
        UUID admin = newUserWith("SYSTEM_ADMIN", AGENCY, true);
        expect(ErrorCode.FORBIDDEN, () -> policy.requireFacility(user(admin), SIDEWALK));
        expect(ErrorCode.FORBIDDEN, () -> policy.requireManagedPatrol(user(admin), PATROL));
        expect(ErrorCode.FORBIDDEN, () -> policy.requireManagedPatrol(user(KIM), PATROL));
        expect(ErrorCode.FORBIDDEN, () -> policy.requireAssignedPatrol(user(CHOI), PATROL));
    }

    @Test
    void activityManagerReachesPatrolsOfHisAgencyOrMeetingPointOnly() {
        assertThat(policy.requireManagedPatrol(user(CHOI), PATROL)).isEqualTo(PROGRAM);

        UUID otherAgency = jdbc.queryForObject("""
                INSERT INTO organizations (program_id, type, name) VALUES (?, 'AGENCY', '가상 다른 복지관') RETURNING id""",
                UUID.class, PROGRAM);
        UUID outsider = newUserWith("ACTIVITY_MANAGER", otherAgency, false);
        expect(ErrorCode.NOT_FOUND, () -> policy.requireManagedPatrol(user(outsider), PATROL));

        // 거점만 맡은 담당자도 그 거점에서 출발하는 순회를 본다.
        UUID pointManager = newUserWith("ACTIVITY_MANAGER", MEETING_POINT, false);
        assertThat(policy.requireManagedPatrol(user(pointManager), PATROL)).isEqualTo(PROGRAM);

        // 권한이 회수되면 즉시 범위에서 빠진다.
        jdbc.update("UPDATE user_role_scopes SET revoked_at = now() WHERE user_id = ? AND role = 'ACTIVITY_MANAGER'", CHOI);
        expect(ErrorCode.FORBIDDEN, () -> policy.requireManagedPatrol(user(CHOI), PATROL));
    }

    @Test
    void includeDescendantsExpandsScopeToChildOrganizationsOnly() {
        UUID flat = newUserWith("PROGRAM_MANAGER", AGENCY, false);
        UUID tree = newUserWith("PROGRAM_MANAGER", AGENCY, true);
        assertThat(policy.coversOrganization(user(flat), "PROGRAM_MANAGER", PROGRAM, AGENCY)).isTrue();
        assertThat(policy.coversOrganization(user(flat), "PROGRAM_MANAGER", PROGRAM, MEETING_POINT)).isFalse();
        assertThat(policy.coversOrganization(user(tree), "PROGRAM_MANAGER", PROGRAM, MEETING_POINT)).isTrue();
        assertThat(policy.coversOrganization(user(tree), "PROGRAM_MANAGER", PROGRAM, ROAD_DEPT)).isFalse();
        // 같은 기관 ID라도 다른 사업으로는 접근할 수 없다.
        UUID otherProgram = jdbc.queryForObject("INSERT INTO programs (name) VALUES ('가상 다른 사업') RETURNING id", UUID.class);
        assertThat(policy.coversOrganization(user(tree), "PROGRAM_MANAGER", otherProgram, AGENCY)).isFalse();
        expect(ErrorCode.NOT_FOUND, () -> policy.requireOrganization(user(flat), "PROGRAM_MANAGER", PROGRAM, MEETING_POINT));
    }

    @Test
    void seniorReachesOnlyOwnActiveAssignment() {
        UUID kimParticipant = UUID.fromString("20000000-0000-4000-8000-000000000001");
        assertThat(policy.requireAssignedPatrol(user(KIM), PATROL)).isEqualTo(kimParticipant);

        UUID stranger = newUserWith("SENIOR", AGENCY, false);
        jdbc.update("INSERT INTO participants (program_id, user_id, organization_id) VALUES (?, ?, ?)", PROGRAM, stranger, AGENCY);
        expect(ErrorCode.NOT_FOUND, () -> policy.requireAssignedPatrol(user(stranger), PATROL));
        expect(ErrorCode.NOT_FOUND, () -> policy.requireAssignedPatrol(user(KIM), UUID.randomUUID()));

        jdbc.update("UPDATE participants SET status = 'SUSPENDED' WHERE id = ?", kimParticipant);
        expect(ErrorCode.NOT_FOUND, () -> policy.requireAssignedPatrol(user(KIM), PATROL));
        jdbc.update("UPDATE participants SET status = 'ACTIVE' WHERE id = ?", kimParticipant);
        jdbc.update("UPDATE patrol_members SET assignment_active = false WHERE patrol_id = ? AND participant_id = ?", PATROL,
                kimParticipant);
        expect(ErrorCode.NOT_FOUND, () -> policy.requireAssignedPatrol(user(KIM), PATROL));
    }

    private AuthenticatedUser user(UUID userId) {
        return new AuthenticatedUser(userId, UUID.randomUUID(), "가상", accounts.roles(userId));
    }

    private UUID newUserWith(String role, UUID organization, boolean includeDescendants) {
        UUID user = jdbc.queryForObject("INSERT INTO users (name) VALUES ('가상') RETURNING id", UUID.class);
        jdbc.update("""
                INSERT INTO user_role_scopes (program_id, user_id, organization_id, role, include_descendants) VALUES (?, ?, ?, ?, ?)""",
                PROGRAM, user, organization, role, includeDescendants);
        return user;
    }

    private static void expect(ErrorCode code, ThrowingCallable call) {
        Consumer<Throwable> check = e -> assertThat(((ApiException) e).code()).isEqualTo(code);
        assertThatThrownBy(call).isInstanceOf(ApiException.class).satisfies(check::accept);
    }
}
