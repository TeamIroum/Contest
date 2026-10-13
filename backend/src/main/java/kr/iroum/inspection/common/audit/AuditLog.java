package kr.iroum.inspection.common.audit;

import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** 감사 기록(audit_events). 토큰·전화번호 평문·사진 URL은 넣지 않는다. 호출자 트랜잭션에 함께 커밋된다. */
@Component
public class AuditLog {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public AuditLog(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID programId, UUID actor, String action, String resourceType, UUID resourceId, String reason,
            Map<String, ?> before, Map<String, ?> after) {
        jdbc.update("""
                INSERT INTO audit_events (program_id, actor_user_id, action, resource_type, resource_id, reason, before_json, after_json, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)""",
                programId, actor, action, resourceType, resourceId, reason, before == null ? null : json.writeValueAsString(before),
                after == null ? null : json.writeValueAsString(after), MDC.get("requestId"));
    }
}
