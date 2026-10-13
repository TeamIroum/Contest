package kr.iroum.inspection.auth.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import kr.iroum.inspection.auth.AuthProperties;
import kr.iroum.inspection.auth.account.AccountRepository;
import kr.iroum.inspection.auth.account.AccountRepository.Account;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/**
 * 로그인 세션 발급·갱신·검증·종료(업무 API 4.1절, D-15).
 * <ul>
 * <li>세션 절대 만료: SENIOR 전용 계정 7일, 관리자 역할이 하나라도 있으면 12시간. 갱신으로 늘리지 않는다.</li>
 * <li>Access 만료: 15분이며 세션 절대 만료를 넘지 않는다.</li>
 * <li>Refresh 회전은 원자 처리하며 이전 토큰 재사용이 탐지되면 그 세션을 종료한다.</li>
 * <li>요청마다 세션 종료 여부, 계정 상태, auth_version(DB 권위값)을 확인한다.</li>
 * </ul>
 */
@Service
public class SessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String MSG_LOGIN_AGAIN = "다시 로그인해 주세요.";
    private static final String MSG_CHANGE_PASSWORD_FIRST = "관리자 로그인에서 비밀번호를 먼저 변경해 주세요.";
    public static final String REASON_PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

    private final SessionStore store;
    private final AccountRepository accounts;
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    public SessionService(SessionStore store, AccountRepository accounts, JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 인증(SMS·시연 OTP·관리자 비밀번호) 성공 뒤 새 세션을 만든다. 호출자가 자격 확인을 끝낸 계정만 넘긴다.
     *
     * @param verifiedAuthVersion 자격을 확인한 시점의 auth_version. 그 사이 비밀번호 변경·초기화·권한 변경으로 버전이 바뀌었으면
     *        발급하지 않는다. 확인 뒤 발급 직전에 바뀌어도 세션은 이 버전에 묶이므로 다음 요청부터 거부된다(새 버전으로 승격하지 않음).
     */
    public IssuedTokens issue(UUID userId, long verifiedAuthVersion, String clientId, String deviceLabel) {
        Account account = accounts.find(userId).filter(Account::active)
                .filter(a -> a.authVersion() == verifiedAuthVersion)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, MSG_LOGIN_AGAIN));
        if (account.passwordChangeRequired()) {
            // D-23: 비밀번호 변경 의무가 있으면 어떤 로그인 경로로도 일반 업무 토큰을 주지 않는다.
            // 변경은 관리자 로그인의 제한 세션으로만 한다(SMS 등 다른 경로로 우회 불가).
            // 프론트가 문장 대신 분기할 수 있도록 details.reason을 준다(OpenAPI 0.2.3 ErrorForbidden).
            throw new ApiException(ErrorCode.FORBIDDEN, MSG_CHANGE_PASSWORD_FIRST, java.util.Map.of("reason", REASON_PASSWORD_CHANGE_REQUIRED));
        }
        Set<String> roles = accounts.roles(userId);
        Instant now = now();
        boolean adminRole = roles.stream().anyMatch(role -> !"SENIOR".equals(role));
        Instant absolute = now.plus(adminRole ? properties.adminSessionTtl() : properties.seniorSessionTtl());
        String refresh = newRefreshToken();
        LoginSession session = new LoginSession(UUID.randomUUID(), userId, verifiedAuthVersion, now, absolute, deviceLabel,
                clientId, now, 0, false);
        store.create(session, hash(refresh), java.time.Duration.between(now, absolute));
        return tokens(session, account, roles, refresh, now);
    }

    /** Refresh 쿠키로 새 Access·Refresh를 발급한다. 재사용·종료·계정 변경이면 AUTH_REQUIRED. */
    public IssuedTokens refresh(String presentedRefreshToken) {
        if (presentedRefreshToken == null || presentedRefreshToken.isBlank()) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, MSG_LOGIN_AGAIN);
        }
        Instant now = now();
        String next = newRefreshToken();
        SessionStore.Rotation rotation = store.rotate(hash(presentedRefreshToken), hash(next), now);
        if (rotation.result() != SessionStore.RotationResult.ROTATED) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, MSG_LOGIN_AGAIN);
        }
        LoginSession session = store.find(rotation.sessionId())
                .filter(s -> s.absoluteExpiresAt().isAfter(now))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, MSG_LOGIN_AGAIN));
        Account account = currentAccount(session);
        return tokens(session, account, accounts.roles(session.userId()), next, now);
    }

    /** Access JWT 서명 검증 뒤 세션·계정 상태를 확인해 인증 주체를 만든다. */
    public AuthenticatedUser authenticate(Jwt jwt) {
        UUID sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
        Instant now = now();
        LoginSession session = store.find(sessionId)
                .filter(s -> !s.revoked())
                .filter(s -> s.absoluteExpiresAt().isAfter(now))
                .filter(s -> s.userId().toString().equals(jwt.getSubject()))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, MSG_LOGIN_AGAIN));
        Number tokenVersion = jwt.getClaim("av");
        if (tokenVersion == null || tokenVersion.longValue() != session.authVersion()) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, MSG_LOGIN_AGAIN);
        }
        Account account = currentAccount(session);
        store.touch(sessionId, now);
        return new AuthenticatedUser(account.id(), sessionId, account.name(), accounts.roles(account.id()));
    }

    public List<LoginSession> sessions(UUID userId) {
        return store.listActive(userId);
    }

    public void revoke(UUID sessionId) {
        store.revoke(sessionId);
    }

    public void revokeAll(UUID userId) {
        store.listActive(userId).forEach(session -> store.revoke(session.id()));
    }

    /** Refresh 쿠키만 있는 로그아웃에서 세션을 찾는다. 회전 상태는 바꾸지 않는다. */
    public void revokeByRefreshToken(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            store.sessionIdForRefresh(hash(refreshToken)).ifPresent(store::revoke);
        }
    }

    /**
     * DB의 계정 상태·auth_version이 세션과 다르면(중지·비밀번호 변경·권한 회수 처리) 세션을 끝낸다.
     * 비밀번호 변경 의무가 생긴 계정의 일반 세션도 끝낸다(D-23, 버전 증가 없이 표시만 바뀐 경우 포함).
     */
    private Account currentAccount(LoginSession session) {
        Account account = accounts.find(session.userId()).orElse(null);
        if (account == null || !account.active() || account.authVersion() != session.authVersion()
                || account.passwordChangeRequired()) {
            store.revoke(session.id());
            throw new ApiException(ErrorCode.AUTH_REQUIRED, MSG_LOGIN_AGAIN);
        }
        return account;
    }

    private IssuedTokens tokens(LoginSession session, Account account, Set<String> roles, String refresh, Instant now) {
        Instant accessExpiry = min(now.plus(properties.accessTokenTtl()), session.absoluteExpiresAt());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(session.userId().toString())
                .issuedAt(now)
                .expiresAt(accessExpiry)
                .id(UUID.randomUUID().toString())
                .claim("sid", session.id().toString())
                .claim("av", session.authVersion())
                .build();
        String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedTokens(access, accessExpiry, refresh, session.absoluteExpiresAt(), session.id(), account.id(), account.name(), roles);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
