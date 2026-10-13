package kr.iroum.inspection.audit;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.patrol.PatrolViews.Page;

/**
 * 감사 이력 조회(기능정의서 OPS-06). 사업 총괄·시스템 관리자만, 자기 사업의 기록만.
 * 수행자·시각·작업·대상·사유·변경 전후를 보여 준다. 전화번호·토큰·비밀번호·주소(URL)로 보이는 칸은 값을 가린다.
 * service/: 원본에는 기록만 있고 조회 API가 없었다(B-2).
 */
@Service
public class AuditQueryService {

    public record Event(UUID id, Instant at, String actorName, String action, String resourceType, UUID resourceId, String reason, JsonNode before,
            JsonNode after) {
    }

    private static final Set<String> HIDDEN = Set.of("phone", "token", "password", "secret", "url", "path", "hash", "ciphertext", "hmac");
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final JsonMapper json;

    public AuditQueryService(JdbcTemplate jdbc, AccessPolicy access, JsonMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Page<Event> list(AuthenticatedUser user, String resourceType, UUID resourceId, String action, LocalDate from, LocalDate to, Integer page,
            Integer size) {
        access.requireAnyRole(user, AccessPolicy.PROGRAM_MANAGER, AccessPolicy.SYSTEM_ADMIN);
        UUID program = jdbc.queryForList("""
                SELECT program_id FROM user_role_scopes WHERE user_id = ? AND role IN ('PROGRAM_MANAGER', 'SYSTEM_ADMIN') AND revoked_at IS NULL
                ORDER BY granted_at LIMIT 1""", UUID.class, user.userId()).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다."));
        int p = page == null ? 0 : page;
        int s = size == null ? 30 : size;
        if (p < 0 || s < 1 || s > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 형식을 확인해 주세요.");
        }
        String where = """
                FROM audit_events a LEFT JOIN users u ON u.id = a.actor_user_id
                WHERE a.program_id = ? AND (?::text IS NULL OR a.resource_type = ?) AND (?::uuid IS NULL OR a.resource_id = ?)
                  AND (?::text IS NULL OR a.action = ?)
                  AND (?::date IS NULL OR (a.created_at AT TIME ZONE 'Asia/Seoul')::date >= ?)
                  AND (?::date IS NULL OR (a.created_at AT TIME ZONE 'Asia/Seoul')::date <= ?)
                """;
        Date f = from == null ? null : Date.valueOf(from);
        Date t = to == null ? null : Date.valueOf(to);
        Object[] args = { program, resourceType, resourceType, resourceId, resourceId, action, action, f, f, t, t };
        List<Event> items = jdbc.query("SELECT a.id, a.created_at, u.name, a.action, a.resource_type, a.resource_id, a.reason, a.before_json::text, a.after_json::text "
                + where + " ORDER BY a.created_at DESC, a.id LIMIT " + s + " OFFSET " + ((long) p * s),
                (rs, i) -> new Event(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getString(7), clean(rs.getString(8)), clean(rs.getString(9))),
                args);
        Long total = jdbc.queryForObject("SELECT count(*) " + where, Long.class, args);
        return new Page<>(items, p, s, total == null ? 0 : total);
    }

    /** 작업 종류 목록(걸러 보기용). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> actions(AuthenticatedUser user) {
        access.requireAnyRole(user, AccessPolicy.PROGRAM_MANAGER, AccessPolicy.SYSTEM_ADMIN);
        return jdbc.queryForList("""
                SELECT a.action, a.resource_type AS "resourceType", count(*) AS count FROM audit_events a
                WHERE a.program_id IN (SELECT program_id FROM user_role_scopes WHERE user_id = ? AND revoked_at IS NULL)
                GROUP BY a.action, a.resource_type ORDER BY count(*) DESC""", user.userId());
    }

    private JsonNode clean(String raw) {
        if (raw == null) {
            return null;
        }
        JsonNode node = json.readTree(raw);
        mask(node);
        return node;
    }

    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode o) {
            Iterator<String> names = o.propertyNames().iterator();
            List<String> keys = new java.util.ArrayList<>();
            names.forEachRemaining(keys::add);
            for (String k : keys) {
                String lower = k.toLowerCase();
                if (HIDDEN.stream().anyMatch(lower::contains)) {
                    o.put(k, "(가림)");
                } else {
                    mask(o.get(k));
                }
            }
        } else if (node != null && node.isArray()) {
            node.forEach(AuditQueryService::mask);
        }
    }
}
