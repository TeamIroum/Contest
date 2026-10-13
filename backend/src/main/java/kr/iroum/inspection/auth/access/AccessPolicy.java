package kr.iroum.inspection.auth.access;

import java.util.Arrays;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/**
 * 역할·사업·기관·시설 관할 검사(업무 API 3절). 업무 API는 객체를 읽기 전에 여기서 범위를 확인한다.
 * <ul>
 * <li>필요한 역할이 없으면 403 FORBIDDEN. 역할 하나가 다른 역할 권한을 포함하지 않는다(SYSTEM_ADMIN도 마찬가지).</li>
 * <li>역할은 있으나 대상이 범위 밖이거나 없으면 404 NOT_FOUND로 존재를 숨긴다.</li>
 * <li>범위는 철회되지 않은 user_role_scopes의 (사업, 기관)이며 include_descendants면 하위 기관까지 포함한다.</li>
 * <li>시설 관할은 시설 대장의 현재 관리 부서(facilities.managing_department_id) 기준이다.</li>
 * <li>시니어 순회는 본인 참여자의 유효 배정(assignment_active)만 허용한다. 활동 중 다른 조로 이동한 시니어는 이전 조의 조회·사진 전송을
 * 위해 참여 이력(TRANSFERRED 구간)으로 확인한다(D-36, requireSeniorParticipant).</li>
 * </ul>
 * 매 호출이 DB 현재 값을 읽으므로 권한 회수·관할 이관이 다음 요청부터 반영된다.
 */
@Component
public class AccessPolicy {

    public static final String SENIOR = "SENIOR";
    public static final String ACTIVITY_MANAGER = "ACTIVITY_MANAGER";
    public static final String FACILITY_MANAGER = "FACILITY_MANAGER";
    public static final String PROGRAM_MANAGER = "PROGRAM_MANAGER";
    public static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    /** 사용자가 role로 접근할 수 있는 (사업, 기관) 집합. 하위 기관 포함 여부를 재귀로 펼친다. */
    private static final String SCOPED_ORGS = """
            WITH RECURSIVE scoped(program_id, id, descend) AS (
                SELECT s.program_id, s.organization_id, s.include_descendants FROM user_role_scopes s
                WHERE s.user_id = ? AND s.role = ? AND s.revoked_at IS NULL
                UNION
                SELECT o.program_id, o.id, true FROM organizations o
                JOIN scoped p ON p.descend AND o.program_id = p.program_id AND o.parent_id = p.id
            )
            """;

    /** 목록 조회용: role 범위 (program_id, id)를 담은 재귀 CTE(scoped). 매개변수는 (userId, role) 순서다. */
    public static String scopedOrganizationsCte() {
        return SCOPED_ORGS;
    }

    private static final String MSG_FORBIDDEN = "이 작업을 할 권한이 없습니다.";
    private static final String MSG_NOT_FOUND = "찾을 수 없거나 접근할 수 없는 항목입니다.";

    private final JdbcTemplate jdbc;

    public AccessPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 나열한 역할 중 하나라도 있어야 한다. 없으면 403. */
    public void requireAnyRole(AuthenticatedUser user, String... roles) {
        if (user == null || Arrays.stream(roles).noneMatch(user::hasRole)) {
            throw new ApiException(ErrorCode.FORBIDDEN, MSG_FORBIDDEN);
        }
    }

    /** role 범위에 해당 사업의 기관이 있는지. */
    public boolean coversOrganization(AuthenticatedUser user, String role, UUID programId, UUID organizationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(SCOPED_ORGS + "SELECT EXISTS (SELECT 1 FROM scoped WHERE program_id = ? AND id = ?)",
                Boolean.class, user.userId(), role, programId, organizationId));
    }

    public void requireOrganization(AuthenticatedUser user, String role, UUID programId, UUID organizationId) {
        requireAnyRole(user, role);
        if (!coversOrganization(user, role, programId, organizationId)) {
            throw notFound();
        }
    }

    /** 시설 담당자: 본인 부서가 시설 대장상 현재 관리하는 시설만. 사업 ID를 돌려준다. */
    public UUID requireFacility(AuthenticatedUser user, UUID facilityId) {
        requireAnyRole(user, FACILITY_MANAGER);
        return jdbc.query(SCOPED_ORGS + """
                SELECT f.program_id FROM facilities f
                JOIN scoped s ON s.program_id = f.program_id AND s.id = f.managing_department_id
                WHERE f.id = ?""", (rs, i) -> rs.getObject(1, UUID.class), user.userId(), FACILITY_MANAGER, facilityId)
                .stream().findFirst().orElseThrow(AccessPolicy::notFound);
    }

    /** 활동 담당자: 순회의 수행기관 또는 거점이 담당 범위에 있어야 한다. 사업 ID를 돌려준다. */
    public UUID requireManagedPatrol(AuthenticatedUser user, UUID patrolId) {
        requireAnyRole(user, ACTIVITY_MANAGER);
        return jdbc.query(SCOPED_ORGS + """
                SELECT p.program_id FROM patrols p
                WHERE p.id = ? AND EXISTS (SELECT 1 FROM scoped s WHERE s.program_id = p.program_id
                    AND s.id IN (p.organization_id, p.base_organization_id))""",
                (rs, i) -> rs.getObject(1, UUID.class), user.userId(), ACTIVITY_MANAGER, patrolId)
                .stream().findFirst().orElseThrow(AccessPolicy::notFound);
    }

    /**
     * 시니어: 본인 참여자가 유효하게 배정된 순회만. 참여자 ID를 돌려준다(요청의 사용자·참여자 ID는 믿지 않는다).
     * 참여자 상태가 ACTIVE가 아니면 거부한다.
     */
    public UUID requireAssignedPatrol(AuthenticatedUser user, UUID patrolId) {
        requireAnyRole(user, SENIOR);
        return jdbc.query("""
                SELECT m.participant_id FROM patrol_members m
                JOIN participants pa ON pa.id = m.participant_id AND pa.program_id = m.program_id
                WHERE m.patrol_id = ? AND m.assignment_active AND pa.user_id = ? AND pa.status = 'ACTIVE'
                  AND EXISTS (SELECT 1 FROM user_role_scopes s WHERE s.user_id = pa.user_id AND s.program_id = m.program_id
                      AND s.role = 'SENIOR' AND s.revoked_at IS NULL)""",
                (rs, i) -> rs.getObject(1, UUID.class), patrolId, user.userId())
                .stream().findFirst().orElseThrow(AccessPolicy::notFound);
    }

    /** 시니어의 순회 배정. active=false면 이 조에서 다른 조로 이동해 나간 배정이다. memberId는 배정 행 ID(assignmentRef). */
    public record SeniorAssignment(UUID participantId, UUID memberId, boolean active) {
    }

    /**
     * 시니어: 본인 참여자가 유효하게 배정됐거나, 이 조에 참여했다가 활동 중 이동으로 나간 순회(D-36). 재편성·취소로 빠진 배정은 제외한다.
     * 조회·사진 전송·멱등 재현에 쓰며, 새 활동 기록은 active일 때만 서비스가 허용한다.
     */
    public SeniorAssignment requireSeniorParticipant(AuthenticatedUser user, UUID patrolId) {
        requireAnyRole(user, SENIOR);
        return jdbc.query("""
                SELECT m.participant_id, m.id, m.assignment_active FROM patrol_members m
                JOIN participants pa ON pa.id = m.participant_id AND pa.program_id = m.program_id
                WHERE m.patrol_id = ? AND pa.user_id = ? AND pa.status = 'ACTIVE'
                  AND (m.assignment_active OR EXISTS (SELECT 1 FROM patrol_participations pp WHERE pp.patrol_id = m.patrol_id
                      AND pp.participant_id = m.participant_id AND pp.leave_reason = 'TRANSFERRED'))
                  AND EXISTS (SELECT 1 FROM user_role_scopes s WHERE s.user_id = pa.user_id AND s.program_id = m.program_id
                      AND s.role = 'SENIOR' AND s.revoked_at IS NULL)""",
                (rs, i) -> new SeniorAssignment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBoolean(3)), patrolId,
                user.userId())
                .stream().findFirst().orElseThrow(AccessPolicy::notFound);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, MSG_NOT_FOUND);
    }
}
