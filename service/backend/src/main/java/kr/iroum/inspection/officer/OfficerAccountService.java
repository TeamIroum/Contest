package kr.iroum.inspection.officer;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.password.PasswordPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse;

/**
 * 공무원(관리자) 계정 발급·역할·비밀번호 초기화·잠금 해제·사용 중지(기능정의서 OPS-07·08, D-04: 시스템 관리자만).
 * <ul>
 * <li>공개 가입은 없다. 발급·초기화하면 임시 비밀번호를 응답에 한 번만 담고, 처음 로그인할 때 본인 비밀번호로 바꾼다(D-23).
 * 임시 비밀번호는 해시로만 저장하며 멱등 결과·감사 기록에도 남기지 않는다(그래서 이 API들은 멱등 키를 받지 않는다).</li>
 * <li>초기화·사용 중지는 인증 버전을 올리고 기존 로그인 세션을 모두 끊는다. 본인 계정은 다른 시스템 관리자가 처리한다.</li>
 * <li>잠금 기준은 로그인 쪽(AdminPasswordService) 값 그대로: 연속 5회 실패 시 10분.</li>
 * </ul>
 * service/: 원본에는 없던 기능이다(B-2).
 */
@Service
public class OfficerAccountService {

    public static final Set<String> ROLES = Set.of(AccessPolicy.ACTIVITY_MANAGER, AccessPolicy.FACILITY_MANAGER, AccessPolicy.PROGRAM_MANAGER,
            AccessPolicy.SYSTEM_ADMIN);

    public record RoleScope(String role, UUID organizationId, String organizationName) {
    }

    public record Account(UUID id, String name, String loginId, String status, boolean passwordChangeRequired, boolean locked, Instant lockedUntil,
            List<RoleScope> roles, long version) {
    }

    /** 임시 비밀번호는 이 응답에만 있다. 다시 조회할 수 없다. */
    public record Issued(Account account, String temporaryPassword) {
    }

    public record Organization(UUID id, String name, String type) {
    }

    public record RoleInput(String role, UUID organizationId) {
    }

    private static final String ALPHA = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%*?";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final AuditLog audit;
    private final PasswordEncoder encoder;
    private final SessionService sessions;
    private final Clock clock;

    public OfficerAccountService(JdbcTemplate jdbc, AccessPolicy access, AuditLog audit, PasswordEncoder encoder, SessionService sessions, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.encoder = encoder;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** 시스템 관리자의 사업 안 관리자 계정(로그인 ID가 있는 계정). */
    @Transactional(readOnly = true)
    public List<Account> list(AuthenticatedUser user) {
        UUID program = program(user);
        List<UUID> ids = jdbc.queryForList("""
                SELECT u.id FROM users u WHERE u.login_id IS NOT NULL
                  AND EXISTS (SELECT 1 FROM user_role_scopes s WHERE s.user_id = u.id AND s.program_id = ? AND s.role <> 'SENIOR')
                ORDER BY u.name, u.login_id""", UUID.class, program);
        return ids.stream().map(this::load).toList();
    }

    /** 역할을 줄 수 있는 기관(같은 사업의 모든 기관). */
    @Transactional(readOnly = true)
    public List<Organization> organizations(AuthenticatedUser user) {
        UUID program = program(user);
        return jdbc.query("""
                SELECT id, name, type FROM organizations WHERE program_id = ?
                ORDER BY CASE type WHEN 'AGENCY' THEN 0 WHEN 'MEETING_POINT' THEN 1 ELSE 2 END, name""",
                (rs, i) -> new Organization(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), program);
    }

    @Transactional
    public Issued issue(AuthenticatedUser user, String name, String loginId, List<RoleInput> roles) {
        UUID program = program(user);
        String cleanName = text(name, 50, "name", "이름은 1~50자로 넣어 주세요.");
        String cleanId = loginId == null ? "" : loginId.strip();
        if (!cleanId.matches("[A-Za-z0-9._-]{4,50}")) {
            throw invalid("loginId", "INVALID", "아이디는 영문·숫자·.-_ 4~50자로 넣어 주세요.");
        }
        Boolean taken = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE lower(login_id) = lower(?))", Boolean.class, cleanId);
        if (Boolean.TRUE.equals(taken)) {
            throw invalid("loginId", "DUPLICATE", "이미 쓰고 있는 아이디예요.");
        }
        List<RoleInput> scopes = roles(program, roles);
        String temp = temporaryPassword();
        UUID id = jdbc.queryForObject("""
                INSERT INTO users (name, login_id, password_hash, password_change_required) VALUES (?, ?, ?, true) RETURNING id""",
                UUID.class, cleanName, cleanId, encoder.encode(temp));
        for (RoleInput r : scopes) {
            jdbc.update("INSERT INTO user_role_scopes (program_id, user_id, organization_id, role, include_descendants) VALUES (?, ?, ?, ?, ?)",
                    program, id, r.organizationId(), r.role(), descend(r.role()));
        }
        audit.record(program, user.userId(), "OFFICER_ACCOUNT_ISSUED", "USER", id, null, null,
                Map.of("name", cleanName, "loginId", cleanId, "roles", scopes.stream().map(r -> r.role() + "@" + r.organizationId()).toList()));
        return new Issued(load(id), temp);
    }

    /** 역할·관할 바꾸기. 빠진 역할은 회수(revoked_at)해 이력으로 남긴다. 본인 계정의 시스템 관리자 역할은 뺄 수 없다. */
    @Transactional
    public Account changeRoles(AuthenticatedUser user, UUID accountId, List<RoleInput> roles, long version) {
        UUID program = program(user);
        Account before = lockOwned(program, accountId, version);
        List<RoleInput> next = roles(program, roles);
        if (accountId.equals(user.userId()) && next.stream().noneMatch(r -> AccessPolicy.SYSTEM_ADMIN.equals(r.role()))) {
            throw invalid("roles", "SELF_ADMIN", "본인 계정의 시스템 관리자 역할은 다른 시스템 관리자가 빼야 해요.");
        }
        Set<String> keep = new LinkedHashSet<>();
        next.forEach(r -> keep.add(r.role() + "@" + r.organizationId()));
        Timestamp now = Timestamp.from(clock.instant());
        for (RoleScope r : before.roles()) {
            if (!keep.contains(r.role() + "@" + r.organizationId())) {
                jdbc.update("""
                        UPDATE user_role_scopes SET revoked_at = ? WHERE user_id = ? AND program_id = ? AND role = ? AND organization_id = ?
                          AND revoked_at IS NULL""", now, accountId, program, r.role(), r.organizationId());
            }
        }
        Set<String> had = new LinkedHashSet<>();
        before.roles().forEach(r -> had.add(r.role() + "@" + r.organizationId()));
        for (RoleInput r : next) {
            if (!had.contains(r.role() + "@" + r.organizationId())) {
                jdbc.update("INSERT INTO user_role_scopes (program_id, user_id, organization_id, role, include_descendants) VALUES (?, ?, ?, ?, ?)",
                        program, accountId, r.organizationId(), r.role(), descend(r.role()));
            }
        }
        jdbc.update("UPDATE users SET version = version + 1, updated_at = now() WHERE id = ?", accountId);
        audit.record(program, user.userId(), "OFFICER_ROLES_CHANGED", "USER", accountId, null, Map.of("roles", had), Map.of("roles", keep));
        return load(accountId);
    }

    /** 임시 비밀번호 재발급: 잠금도 풀고, 기존 로그인 세션을 모두 끊는다. 본인 계정은 다른 시스템 관리자가 처리한다. */
    @Transactional
    public Issued resetPassword(AuthenticatedUser user, UUID accountId, String reason) {
        UUID program = program(user);
        notSelf(user, accountId, "본인 계정의 비밀번호 초기화는 다른 시스템 관리자가 해야 해요.");
        lockOwned(program, accountId, null);
        String temp = temporaryPassword();
        jdbc.update("""
                UPDATE users SET password_hash = ?, password_change_required = true, password_changed_at = NULL, password_failed_count = 0,
                    locked_until = NULL, auth_version = auth_version + 1, version = version + 1, updated_at = now() WHERE id = ?""",
                encoder.encode(temp), accountId);
        sessions.revokeAll(accountId);
        audit.record(program, user.userId(), "OFFICER_PASSWORD_RESET", "USER", accountId, reason(reason), null, Map.of("passwordChangeRequired", true));
        return new Issued(load(accountId), temp);
    }

    @Transactional
    public Account unlock(AuthenticatedUser user, UUID accountId, String reason) {
        UUID program = program(user);
        lockOwned(program, accountId, null);
        jdbc.update("UPDATE users SET password_failed_count = 0, locked_until = NULL, version = version + 1, updated_at = now() WHERE id = ?", accountId);
        audit.record(program, user.userId(), "OFFICER_UNLOCKED", "USER", accountId, reason(reason), null, null);
        return load(accountId);
    }

    /** 사용 중지(SUSPENDED)하면 로그인 세션을 모두 끊는다. 본인 계정은 멈출 수 없다. */
    @Transactional
    public Account changeStatus(AuthenticatedUser user, UUID accountId, String status, String reason, long version) {
        UUID program = program(user);
        if (!List.of("ACTIVE", "SUSPENDED").contains(status)) {
            throw invalid("status", "INVALID", "상태를 다시 골라 주세요.");
        }
        notSelf(user, accountId, "본인 계정은 다른 시스템 관리자가 멈춰야 해요.");
        Account before = lockOwned(program, accountId, version);
        if (before.status().equals(status)) {
            return before;
        }
        jdbc.update("UPDATE users SET status = ?, auth_version = auth_version + 1, version = version + 1, updated_at = now() WHERE id = ?", status,
                accountId);
        if ("SUSPENDED".equals(status)) {
            sessions.revokeAll(accountId);
        }
        audit.record(program, user.userId(), "OFFICER_STATUS_CHANGED", "USER", accountId, reason(reason), Map.of("status", before.status()),
                Map.of("status", status));
        return load(accountId);
    }

    /** 시스템 관리자의 사업. 시연·운영 모두 사업 하나라 첫 범위의 사업을 쓴다. */
    private UUID program(AuthenticatedUser user) {
        access.requireAnyRole(user, AccessPolicy.SYSTEM_ADMIN);
        return jdbc.queryForList("""
                SELECT program_id FROM user_role_scopes WHERE user_id = ? AND role = 'SYSTEM_ADMIN' AND revoked_at IS NULL ORDER BY granted_at LIMIT 1""",
                UUID.class, user.userId()).stream().findFirst().orElseThrow(OfficerAccountService::notFound);
    }

    private Account lockOwned(UUID program, UUID accountId, Long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.version FROM users u WHERE u.id = ? AND u.login_id IS NOT NULL
                  AND EXISTS (SELECT 1 FROM user_role_scopes s WHERE s.user_id = u.id AND s.program_id = ? AND s.role <> 'SENIOR')
                FOR UPDATE OF u""", accountId, program);
        if (rows.isEmpty()) {
            throw notFound();
        }
        long current = ((Number) rows.get(0).get("version")).longValue();
        if (version != null && current != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.", Map.of("currentVersion", current));
        }
        return load(accountId);
    }

    private List<RoleInput> roles(UUID program, List<RoleInput> roles) {
        if (roles == null || roles.isEmpty() || roles.size() > 10) {
            throw invalid("roles", "COUNT", "역할을 하나 이상 골라 주세요.");
        }
        Map<String, RoleInput> unique = new LinkedHashMap<>();
        for (RoleInput r : roles) {
            if (r == null || !ROLES.contains(r.role()) || r.organizationId() == null) {
                throw invalid("roles", "INVALID", "역할과 기관을 다시 골라 주세요.");
            }
            Boolean ok = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM organizations WHERE id = ? AND program_id = ?)", Boolean.class,
                    r.organizationId(), program);
            if (!Boolean.TRUE.equals(ok)) {
                throw invalid("roles", "INVALID", "역할과 기관을 다시 골라 주세요.");
            }
            unique.put(r.role() + "@" + r.organizationId(), r);
        }
        return new ArrayList<>(unique.values());
    }

    /** 활동 담당자·사업 총괄·시스템 관리자는 기관 아래 거점까지, 시설 담당자는 그 부서만. */
    private static boolean descend(String role) {
        return !AccessPolicy.FACILITY_MANAGER.equals(role);
    }

    private Account load(UUID id) {
        Map<String, Object> u = jdbc.queryForMap("""
                SELECT id, name, login_id, status, password_change_required, locked_until, version FROM users WHERE id = ?""", id);
        List<RoleScope> roles = jdbc.query("""
                SELECT s.role, s.organization_id, o.name FROM user_role_scopes s JOIN organizations o ON o.id = s.organization_id
                WHERE s.user_id = ? AND s.revoked_at IS NULL AND s.role <> 'SENIOR'
                ORDER BY CASE s.role WHEN 'ACTIVITY_MANAGER' THEN 0 WHEN 'FACILITY_MANAGER' THEN 1 WHEN 'PROGRAM_MANAGER' THEN 2 ELSE 3 END, o.name""",
                (rs, i) -> new RoleScope(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3)), id);
        Object lu = u.get("locked_until");
        Instant lockedUntil = lu instanceof Timestamp t ? t.toInstant() : lu instanceof OffsetDateTime o ? o.toInstant() : null;
        boolean locked = lockedUntil != null && lockedUntil.isAfter(clock.instant());
        return new Account(id, (String) u.get("name"), (String) u.get("login_id"), (String) u.get("status"), (Boolean) u.get("password_change_required"),
                locked, locked ? lockedUntil : null, roles, ((Number) u.get("version")).longValue());
    }

    /** 영문 대·소문자, 숫자, 기호가 모두 든 12자. 헷갈리는 글자(0·O·1·l·I)는 뺀다. */
    static String temporaryPassword() {
        while (true) {
            StringBuilder b = new StringBuilder();
            String all = ALPHA + DIGITS + SYMBOLS;
            for (int i = 0; i < 12; i++) {
                b.append(all.charAt(RANDOM.nextInt(all.length())));
            }
            String p = b.toString();
            if (PasswordPolicy.satisfiedBy(p) && p.chars().anyMatch(Character::isUpperCase) && p.chars().anyMatch(Character::isLowerCase)) {
                return p;
            }
        }
    }

    private static void notSelf(AuthenticatedUser user, UUID accountId, String message) {
        if (user.userId().equals(accountId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, message);
        }
    }

    private static String reason(String reason) {
        String r = reason == null ? "" : reason.strip();
        if (r.isEmpty() || r.length() > 500) {
            throw invalid("reason", "REQUIRED", "사유를 1~500자로 넣어 주세요.");
        }
        return r;
    }

    private static String text(String value, int max, String field, String message) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > max) {
            throw invalid(field, "INVALID", message);
        }
        return v;
    }

    private static ApiException invalid(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, null, List.of(new ErrorResponse.FieldError(field, reason, message)));
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
    }
}
