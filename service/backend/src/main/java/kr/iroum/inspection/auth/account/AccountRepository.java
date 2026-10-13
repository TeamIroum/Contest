package kr.iroum.inspection.auth.account;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 인증에 필요한 계정·역할 조회. PostgreSQL이 계정 상태와 auth_version의 권위 저장소다(DB 설계 14절). */
@Repository
public class AccountRepository {

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Account(UUID id, String name, String status, long authVersion, boolean passwordChangeRequired,
            Instant lockedUntil) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record Scope(String role, UUID organizationId, String organizationName, String organizationType) {
    }

    public Optional<Account> find(UUID userId) {
        return jdbc.query("""
                SELECT id, name, status, auth_version, password_change_required, locked_until FROM users WHERE id = ?""",
                (rs, i) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getBoolean(5), rs.getObject(6, java.time.OffsetDateTime.class) == null ? null
                                : rs.getObject(6, java.time.OffsetDateTime.class).toInstant()),
                userId).stream().findFirst();
    }

    /** 유효한(철회되지 않은) 역할. 역할 변경은 다음 요청부터 반영된다. */
    public Set<String> roles(UUID userId) {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT DISTINCT role FROM user_role_scopes WHERE user_id = ? AND revoked_at IS NULL", String.class, userId));
    }

    public List<Scope> scopes(UUID userId) {
        return jdbc.query("""
                SELECT s.role, o.id, o.name, o.type FROM user_role_scopes s JOIN organizations o ON o.id = s.organization_id
                WHERE s.user_id = ? AND s.revoked_at IS NULL ORDER BY s.role, o.name""",
                (rs, i) -> new Scope(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)), userId);
    }

    public Optional<UUID> activeParticipantId(UUID userId) {
        return jdbc.query("""
                SELECT id FROM participants WHERE user_id = ? AND status = 'ACTIVE' ORDER BY created_at LIMIT 1""",
                (rs, i) -> rs.getObject(1, UUID.class), userId).stream().findFirst();
    }

    /**
     * 인증번호 로그인 대상: 전화 HMAC이 일치하고, 계정이 ACTIVE이며, 유효한 SENIOR 역할과 ACTIVE 참여자가 있는 계정.
     * 관리자가 등록·활성화한 번호만 인증한다(업무 API 4.2절).
     */
    public record PhoneAccount(UUID id, long authVersion) {
    }

    public Optional<PhoneAccount> findSeniorByPhoneHmac(byte[] phoneHmac) {
        return jdbc.query("""
                SELECT u.id, u.auth_version FROM users u
                WHERE u.phone_lookup_hmac = ? AND u.status = 'ACTIVE'
                  AND EXISTS (SELECT 1 FROM user_role_scopes s WHERE s.user_id = u.id AND s.role = 'SENIOR' AND s.revoked_at IS NULL)
                  AND EXISTS (SELECT 1 FROM participants p WHERE p.user_id = u.id AND p.status = 'ACTIVE')""",
                (rs, i) -> new PhoneAccount(rs.getObject(1, UUID.class), rs.getLong(2)), phoneHmac).stream().findFirst();
    }
}
