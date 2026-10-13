package kr.iroum.inspection.auth.password;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.AuthProperties;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse;

/**
 * 관리자 비밀번호 확인·잠금(D-16)과 비밀번호 변경 제한 세션·변경(D-22·D-23).
 * 계정 행을 FOR UPDATE로 잠가 실패 횟수·잠금·변경 경쟁을 직렬화한다. 잠금 상태 갱신은 응답 오류와 함께 커밋되어야 하므로
 * 실패도 정상 반환값으로 돌려주고, 예외 변환은 트랜잭션 밖(호출자)에서 한다.
 */
@Service
public class AdminPasswordService {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String MSG_LOGIN_FAILED = "아이디 또는 비밀번호를 확인해 주세요.";

    /** 로그인 결과. 잠금·실패도 값으로 돌려줘 실패 횟수 갱신이 롤백되지 않게 한다. */
    public sealed interface LoginResult {
        /** authVersion: 비밀번호를 확인한 시점의 값. 세션 발급에 그대로 넘긴다. */
        record Success(UUID userId, long authVersion) implements LoginResult {
        }

        record ChangeRequired(String restrictedToken, Instant expiresAt) implements LoginResult {
        }

        record Failed() implements LoginResult {
        }

        record Locked(Duration retryAfter) implements LoginResult {
        }
    }

    public sealed interface ChangeResult {
        record Changed(UUID userId) implements ChangeResult {
        }

        record Rejected(ApiException error) implements ChangeResult {
        }
    }

    private record Credential(UUID id, String status, String passwordHash, boolean changeRequired, int failedCount,
            Instant lockedUntil, long authVersion, boolean adminRole) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final String dummyHash;

    public AdminPasswordService(JdbcTemplate jdbc, PasswordEncoder encoder, AuthProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
        // 없는 ID에도 같은 비용의 해시 비교를 해 응답 시간으로 계정 존재를 알 수 없게 한다.
        this.dummyHash = encoder.encode("dummy-Password-1!");
    }

    @Transactional
    public LoginResult login(String loginId, String password) {
        Optional<Credential> found = lockByLoginId(loginId);
        if (found.isEmpty()) {
            encoder.matches(password, dummyHash);
            return new LoginResult.Failed();
        }
        Credential user = found.get();
        Instant now = clock.instant();
        Optional<Duration> locked = lockState(user, now);
        if (locked.isPresent()) {
            // 잠금 중에는 비밀번호를 확인하지 않고 잠금도 연장하지 않는다.
            return new LoginResult.Locked(locked.get());
        }
        boolean matches = encoder.matches(password, user.passwordHash());
        if (!matches) {
            return recordFailure(user, now).<LoginResult>map(LoginResult.Locked::new).orElseGet(LoginResult.Failed::new);
        }
        // 중지 계정·관리자 역할 없는 계정은 비밀번호가 맞아도 같은 실패로 응답한다(실패 횟수는 세지 않음).
        if (!"ACTIVE".equals(user.status()) || !user.adminRole()) {
            return new LoginResult.Failed();
        }
        jdbc.update("UPDATE users SET password_failed_count = 0, locked_until = NULL, updated_at = now() WHERE id = ?", user.id());
        if (user.changeRequired()) {
            return issueRestricted(user, now);
        }
        return new LoginResult.Success(user.id(), user.authVersion());
    }

    /**
     * 비밀번호 변경. 제한 세션 토큰 또는 일반 로그인 사용자(본인) 중 하나로 인증한다.
     * 성공 시 해시 갱신·auth_version 증가·변경 의무 해제·제한 세션 소진을 한 트랜잭션으로 처리한다.
     * <p>
     * 잠금 순서는 로그인·변경 모두 사용자 행 → 제한 세션 행이다. 같은 계정의 다른 제한 세션으로 동시에 바꿀 때
     * 각자 제한 세션 행을 먼저 잠그면 전체 소진 UPDATE와 교착하므로, 제한 세션은 잠그지 않고 사용자만 찾은 뒤
     * 사용자 행을 잠그고 나서 제한 세션을 잠가 소진·만료·auth_version을 다시 확인한다. 먼저 끝난 변경이
     * 다른 제한 세션을 모두 소진하므로 뒤의 요청은 401로 거부된다.
     */
    @Transactional
    public ChangeResult change(String restrictedToken, UUID bearerUserId, String currentPassword, String newPassword) {
        Instant now = clock.instant();
        UUID userId;
        if (bearerUserId != null) {
            userId = bearerUserId;
        } else {
            // 사용자 식별만 한다(잠그지 않음). 판단은 사용자 행을 잠근 뒤에 한다.
            Optional<UUID> owner = restrictedToken == null ? Optional.empty()
                    : jdbc.queryForList("SELECT user_id FROM password_change_sessions WHERE secret_hash = ?", UUID.class,
                            hash(restrictedToken)).stream().findFirst();
            if (owner.isEmpty()) {
                return rejected(ErrorCode.AUTH_REQUIRED, "다시 로그인해 주세요.");
            }
            userId = owner.get();
        }
        Credential user = lockById(userId).orElse(null);
        if (user == null || !"ACTIVE".equals(user.status()) || user.passwordHash() == null) {
            return rejected(ErrorCode.AUTH_REQUIRED, "다시 로그인해 주세요.");
        }
        if (bearerUserId == null) {
            Optional<Map<String, Object>> session = jdbc.queryForList("""
                    SELECT user_id, auth_version, expires_at, consumed_at FROM password_change_sessions
                    WHERE secret_hash = ? FOR UPDATE""", hash(restrictedToken)).stream().findFirst();
            if (session.isEmpty() || !userId.equals(session.get().get("user_id")) || session.get().get("consumed_at") != null
                    || !toInstant(session.get().get("expires_at")).isAfter(now)
                    || user.authVersion() != ((Number) session.get().get("auth_version")).longValue()) {
                return rejected(ErrorCode.AUTH_REQUIRED, "다시 로그인해 주세요.");
            }
        }
        Optional<Duration> locked = lockState(user, now);
        if (locked.isPresent()) {
            return new ChangeResult.Rejected(rateLimited(locked.get()));
        }
        if (!encoder.matches(currentPassword, user.passwordHash())) {
            Optional<Duration> nowLocked = recordFailure(user, now);
            return nowLocked.<ChangeResult>map(d -> new ChangeResult.Rejected(rateLimited(d)))
                    .orElseGet(() -> rejected(ErrorCode.AUTH_REQUIRED, "현재 비밀번호를 확인해 주세요."));
        }
        if (currentPassword.equals(newPassword)) {
            return new ChangeResult.Rejected(invalidNewPassword("현재 비밀번호와 다른 비밀번호를 입력해 주세요."));
        }
        jdbc.update("""
                UPDATE users SET password_hash = ?, password_change_required = false, password_changed_at = ?,
                    password_failed_count = 0, locked_until = NULL, auth_version = auth_version + 1,
                    version = version + 1, updated_at = now()
                WHERE id = ?""", encoder.encode(newPassword), Timestamp.from(now), userId);
        jdbc.update("UPDATE password_change_sessions SET consumed_at = ? WHERE user_id = ? AND consumed_at IS NULL",
                Timestamp.from(now), userId);
        return new ChangeResult.Changed(userId);
    }

    /** 제한 세션 로그아웃(D-23). 없는 토큰이어도 조용히 끝낸다. */
    @Transactional
    public void endRestricted(String restrictedToken) {
        if (restrictedToken != null && !restrictedToken.isBlank()) {
            jdbc.update("UPDATE password_change_sessions SET consumed_at = ? WHERE secret_hash = ? AND consumed_at IS NULL",
                    Timestamp.from(clock.instant()), hash(restrictedToken));
        }
    }

    public static ApiException invalidNewPassword(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null,
                List.of(new ErrorResponse.FieldError("newPassword", "INVALID_FORMAT", message)));
    }

    public static ApiException loginFailed() {
        return new ApiException(ErrorCode.AUTH_REQUIRED, MSG_LOGIN_FAILED);
    }

    public static ApiException rateLimited(Duration retryAfter) {
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        return new ApiException(ErrorCode.RATE_LIMITED, "잠시 후 다시 시도해 주세요.", Map.of("retryAfterSeconds", seconds));
    }

    private LoginResult issueRestricted(Credential user, Instant now) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(properties.restrictedSessionTtl());
        jdbc.update("""
                INSERT INTO password_change_sessions (user_id, secret_hash, auth_version, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)""", user.id(), hash(token), user.authVersion(), Timestamp.from(issuedAt),
                Timestamp.from(expiresAt));
        return new LoginResult.ChangeRequired(token, expiresAt);
    }

    /** 잠금 중이면 남은 시간. 잠금이 끝났으면 카운터를 초기화한다(D-16). */
    private Optional<Duration> lockState(Credential user, Instant now) {
        if (user.lockedUntil() == null) {
            return Optional.empty();
        }
        if (user.lockedUntil().isAfter(now)) {
            return Optional.of(Duration.between(now, user.lockedUntil()));
        }
        jdbc.update("UPDATE users SET password_failed_count = 0, locked_until = NULL WHERE id = ?", user.id());
        return Optional.empty();
    }

    /** 실패를 기록한다. 연속 5회째면 10분 잠그고 남은 시간을 돌려준다. */
    private Optional<Duration> recordFailure(Credential user, Instant now) {
        int failures = user.lockedUntil() != null && !user.lockedUntil().isAfter(now) ? 1 : user.failedCount() + 1;
        if (failures >= MAX_FAILURES) {
            jdbc.update("UPDATE users SET password_failed_count = ?, locked_until = ? WHERE id = ?", failures,
                    Timestamp.from(now.plus(LOCK)), user.id());
            return Optional.of(LOCK);
        }
        jdbc.update("UPDATE users SET password_failed_count = ? WHERE id = ?", failures, user.id());
        return Optional.empty();
    }

    private Optional<Credential> lockByLoginId(String loginId) {
        return lock("u.login_id = ?", loginId);
    }

    private Optional<Credential> lockById(UUID userId) {
        return lock("u.id = ?", userId);
    }

    private Optional<Credential> lock(String where, Object arg) {
        return jdbc.query("""
                SELECT u.id, u.status, u.password_hash, u.password_change_required, u.password_failed_count, u.locked_until,
                    u.auth_version,
                    EXISTS (SELECT 1 FROM user_role_scopes s WHERE s.user_id = u.id AND s.role <> 'SENIOR' AND s.revoked_at IS NULL)
                FROM users u WHERE %s FOR UPDATE OF u""".formatted(where),
                (rs, i) -> new Credential(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getInt(5), toInstant(rs.getObject(6, OffsetDateTime.class)), rs.getLong(7), rs.getBoolean(8)),
                arg).stream().findFirst();
    }

    private static ChangeResult rejected(ErrorCode code, String message) {
        return new ChangeResult.Rejected(new ApiException(code, message));
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw new IllegalArgumentException("시각 형식이 아닙니다: " + value.getClass());
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
