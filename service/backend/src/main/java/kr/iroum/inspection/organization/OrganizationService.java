package kr.iroum.inspection.organization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse;

/**
 * 기관·거점 관리(기능정의서 OPS-01, 업무 API /admin/organizations). 사업 총괄만, 자기 사업 안에서.
 * <ul>
 * <li>유형 규칙: 수행기관(AGENCY)과 부서(DEPARTMENT)는 같은 유형 아래에만, 집결 거점(MEETING_POINT)은 수행기관 아래에만 둔다.</li>
 * <li>상위 기관을 바꿀 때 자기 자신이나 자기 하위로 옮기면(순환) 거절한다.</li>
 * <li>사용 중지(INACTIVE)해도 지난 순회·사건 기록은 그대로다. 이름과 상태, 상위 기관만 바꾼다(유형은 바꾸지 않는다).</li>
 * </ul>
 * service/: 원본에는 없던 기능이다(B-2).
 */
@Service
public class OrganizationService {

    public record Organization(UUID id, String type, String name, UUID parentId, String parentName, String status, int people, long version) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final AuditLog audit;

    public OrganizationService(JdbcTemplate jdbc, AccessPolicy access, AuditLog audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Organization> list(AuthenticatedUser user) {
        UUID program = program(user);
        return jdbc.query(SELECT + " WHERE o.program_id = ? ORDER BY CASE o.type WHEN 'AGENCY' THEN 0 WHEN 'MEETING_POINT' THEN 1 ELSE 2 END, o.name",
                this::map, program);
    }

    @Transactional
    public Organization create(AuthenticatedUser user, String type, String name, UUID parentId) {
        UUID program = program(user);
        if (!List.of("AGENCY", "DEPARTMENT", "MEETING_POINT").contains(type)) {
            throw invalid("type", "INVALID", "유형을 다시 골라 주세요.");
        }
        String clean = name(name);
        requireParent(program, type, parentId, null);
        UUID id = jdbc.queryForObject("INSERT INTO organizations (program_id, parent_id, type, name) VALUES (?, ?, ?, ?) RETURNING id", UUID.class, program,
                parentId, type, clean);
        audit.record(program, user.userId(), "ORGANIZATION_CREATED", "ORGANIZATION", id, null, null,
                Map.of("type", type, "name", clean, "parentId", parentId == null ? "" : parentId.toString()));
        return load(id);
    }

    @Transactional
    public Organization update(AuthenticatedUser user, UUID id, String name, String status, UUID parentId, boolean changeParent, long version) {
        UUID program = program(user);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT type, version FROM organizations WHERE id = ? AND program_id = ? FOR UPDATE", id, program);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
        }
        long current = ((Number) rows.get(0).get("version")).longValue();
        if (current != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.", Map.of("currentVersion", current));
        }
        Organization before = load(id);
        String nextName = name == null ? before.name() : name(name);
        String nextStatus = status == null ? before.status() : status;
        if (!List.of("ACTIVE", "INACTIVE").contains(nextStatus)) {
            throw invalid("status", "INVALID", "상태를 다시 골라 주세요.");
        }
        UUID nextParent = changeParent ? parentId : before.parentId();
        if (changeParent) {
            requireParent(program, before.type(), nextParent, id);
        }
        jdbc.update("UPDATE organizations SET name = ?, status = ?, parent_id = ?, version = version + 1, updated_at = now() WHERE id = ?", nextName,
                nextStatus, nextParent, id);
        Organization after = load(id);
        audit.record(program, user.userId(), "ORGANIZATION_UPDATED", "ORGANIZATION", id, null,
                Map.of("name", before.name(), "status", before.status(), "parentId", String.valueOf(before.parentId())),
                Map.of("name", after.name(), "status", after.status(), "parentId", String.valueOf(after.parentId())));
        return after;
    }

    /** 유형에 맞는 상위 기관인지, 옮길 때 순환이 생기지 않는지. */
    private void requireParent(UUID program, String type, UUID parentId, UUID self) {
        if (parentId == null) {
            if ("MEETING_POINT".equals(type)) {
                throw invalid("parentId", "REQUIRED", "집결 거점은 수행기관 아래에 두어야 해요.");
            }
            return;
        }
        List<Map<String, Object>> parent = jdbc.queryForList("SELECT type FROM organizations WHERE id = ? AND program_id = ?", parentId, program);
        if (parent.isEmpty()) {
            throw invalid("parentId", "INVALID", "상위 기관을 다시 골라 주세요.");
        }
        String pt = (String) parent.get(0).get("type");
        boolean ok = switch (type) {
            case "MEETING_POINT" -> "AGENCY".equals(pt);
            case "AGENCY" -> "AGENCY".equals(pt);
            default -> "DEPARTMENT".equals(pt);
        };
        if (!ok) {
            throw invalid("parentId", "TYPE", "이 유형은 그 기관 아래에 둘 수 없어요. 수행기관·부서는 같은 유형 아래, 집결 거점은 수행기관 아래에 둡니다.");
        }
        if (self != null) {
            Boolean cycle = jdbc.queryForObject("""
                    WITH RECURSIVE up(id, parent_id) AS (
                        SELECT id, parent_id FROM organizations WHERE id = ?
                        UNION SELECT o.id, o.parent_id FROM organizations o JOIN up ON o.id = up.parent_id
                    ) SELECT EXISTS (SELECT 1 FROM up WHERE id = ?)""", Boolean.class, parentId, self);
            if (Boolean.TRUE.equals(cycle)) {
                throw invalid("parentId", "CYCLE", "자기 자신이나 자기 아래 기관 밑으로는 옮길 수 없어요.");
            }
        }
    }

    private UUID program(AuthenticatedUser user) {
        access.requireAnyRole(user, AccessPolicy.PROGRAM_MANAGER);
        return jdbc.queryForList("""
                SELECT program_id FROM user_role_scopes WHERE user_id = ? AND role = 'PROGRAM_MANAGER' AND revoked_at IS NULL ORDER BY granted_at LIMIT 1""",
                UUID.class, user.userId()).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다."));
    }

    private static final String SELECT = """
            SELECT o.id, o.type, o.name, o.parent_id, p.name AS parent_name, o.status, o.version,
                   (SELECT count(DISTINCT s.user_id) FROM user_role_scopes s WHERE s.organization_id = o.id AND s.revoked_at IS NULL) AS people
            FROM organizations o LEFT JOIN organizations p ON p.id = o.parent_id""";

    private Organization load(UUID id) {
        return jdbc.query(SELECT + " WHERE o.id = ?", this::map, id).get(0);
    }

    private Organization map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Organization(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("name"), rs.getObject("parent_id", UUID.class),
                rs.getString("parent_name"), rs.getString("status"), rs.getInt("people"), rs.getLong("version"));
    }

    private static String name(String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > 100) {
            throw invalid("name", "INVALID", "이름은 1~100자로 넣어 주세요.");
        }
        return v;
    }

    private static ApiException invalid(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, null, List.of(new ErrorResponse.FieldError(field, reason, message)));
    }
}
