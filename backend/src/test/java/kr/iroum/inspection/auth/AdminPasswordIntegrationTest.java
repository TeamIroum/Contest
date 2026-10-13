package kr.iroum.inspection.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;

/**
 * 관리자 로그인·잠금(D-16), 비밀번호 규칙(D-22), 임시 비밀번호 제한 세션과 변경(D-23).
 * 비밀번호는 모두 테스트 전용 가상 값이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminPasswordIntegrationTest {

    static final String PASSWORD = "Test-only-pw1";
    static final String TEMPORARY = "Temp-only-pw2";
    static final String NEW_PASSWORD = "New-only-pw3";
    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    AuthTestSupport support;
    AuthTestSupport.Csrf csrf;

    @BeforeEach
    void setUp() throws Exception {
        support = new AuthTestSupport(jdbc);
        csrf = AuthTestSupport.csrf(mvc);
    }

    @Test
    void adminLogsInWithTwelveHourSession() throws Exception {
        String loginId = admin(PASSWORD, false);
        MvcResult result = login(loginId, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.roles[0]").value("FACILITY_MANAGER"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        Instant access = Instant.parse(JsonPath.read(body, "$.accessTokenExpiresAt"));
        Instant absolute = Instant.parse(JsonPath.read(body, "$.refreshTokenExpiresAt"));
        assertThat(Duration.between(access.minus(Duration.ofMinutes(15)), absolute)).isEqualTo(Duration.ofHours(12));
        assertThat(result.getResponse().getCookie("__Host-itda-refresh")).isNotNull();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + JsonPath.read(body, "$.accessToken")))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPasswordUnknownIdSuspendedAndSeniorOnlyAllFailAlike() throws Exception {
        String loginId = admin(PASSWORD, false);
        login(loginId, "Wrong-pw-99").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        login("no-such-admin", PASSWORD).andExpect(status().isUnauthorized());

        String suspended = admin(PASSWORD, false);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE login_id = ?", suspended);
        login(suspended, PASSWORD).andExpect(status().isUnauthorized());

        String seniorOnly = admin(PASSWORD, false);
        jdbc.update("UPDATE user_role_scopes SET revoked_at = now() WHERE user_id = (SELECT id FROM users WHERE login_id = ?)",
                seniorOnly);
        login(seniorOnly, PASSWORD).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/admin/sessions").contentType("application/json").content(loginBody(loginId, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void fiveFailuresLockForTenMinutesWithoutExtendingAndUnlockResetsCounter() throws Exception {
        String loginId = admin(PASSWORD, false);
        for (int i = 0; i < 4; i++) {
            login(loginId, "Wrong-pw-99").andExpect(status().isUnauthorized());
        }
        login(loginId, "Wrong-pw-99").andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "600"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").value(600));
        Instant lockedUntil = lockedUntil(loginId);
        // 잠금 중에는 맞는 비밀번호도 확인하지 않으며, 실패 요청이 잠금을 늘리지 않는다.
        login(loginId, PASSWORD).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        login(loginId, "Wrong-pw-99").andExpect(status().isTooManyRequests());
        assertThat(lockedUntil(loginId)).isEqualTo(lockedUntil);

        // 잠금이 끝나면 카운터를 초기화한다: 4회 더 틀려도 잠기지 않는다.
        jdbc.update("UPDATE users SET locked_until = now() - interval '1 second' WHERE login_id = ?", loginId);
        for (int i = 0; i < 4; i++) {
            login(loginId, "Wrong-pw-99").andExpect(status().isUnauthorized());
        }
        login(loginId, PASSWORD).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT password_failed_count FROM users WHERE login_id = ?", Integer.class, loginId)).isZero();
    }

    @Test
    void temporaryPasswordGivesOnlyRestrictedSessionThatBusinessApisReject() throws Exception {
        String loginId = admin(TEMPORARY, true);
        MvcResult result = login(loginId, TEMPORARY).andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.restrictedSessionExpiresAt").isString())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getCookie("__Host-itda-refresh")).isNull();
        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(c -> c.startsWith("__Host-password-change=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("Max-Age=600", "Path=/", "Secure", "HttpOnly", "SameSite=Strict").doesNotContain("Domain");
        Cookie restricted = result.getResponse().getCookie("__Host-password-change");

        mvc.perform(get("/api/v1/me").cookie(restricted)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/sessions").cookie(restricted)).andExpect(status().isUnauthorized());
        mvc.perform(csrf.apply(post("/api/v1/auth/token/refresh")).cookie(restricted)).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordRulesAreEnforcedOnChange() throws Exception {
        String loginId = admin(TEMPORARY, true);
        Cookie restricted = restrictedCookie(loginId);
        for (String invalid : List.of("Short1!", "NoDigits!!", "nosymbol12", "12345678!!", "공백 기호 1a", "abcd 1234")) {
            change(restricted, TEMPORARY, invalid).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"))
                    .andExpect(jsonPath("$.fieldErrors[0].reason").value("INVALID_FORMAT"));
        }
        change(restricted, TEMPORARY, TEMPORARY).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));
        change(restricted, "Wrong-pw-99", NEW_PASSWORD).andExpect(status().isUnauthorized());
        // 규칙 위반·현재 비밀번호 오류 뒤에도 제한 세션은 유효하다.
        change(restricted, TEMPORARY, " " + NEW_PASSWORD + " ").andExpect(status().isNoContent());
        // 앞뒤 공백을 임의로 제거하지 않는다.
        login(loginId, NEW_PASSWORD).andExpect(status().isUnauthorized());
        login(loginId, " " + NEW_PASSWORD + " ").andExpect(status().isOk());
    }

    @Test
    void changeSucceedsOnceThenRequiresFreshLogin() throws Exception {
        String loginId = admin(TEMPORARY, true);
        Cookie restricted = restrictedCookie(loginId);
        long authVersion = authVersion(loginId);
        MvcResult changed = change(restricted, TEMPORARY, NEW_PASSWORD).andExpect(status().isNoContent()).andReturn();
        assertThat(changed.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(c -> assertThat(c).startsWith("__Host-password-change=").contains("Max-Age=0"))
                .anySatisfy(c -> assertThat(c).startsWith("__Host-itda-refresh=").contains("Max-Age=0"));
        assertThat(authVersion(loginId)).isEqualTo(authVersion + 1);
        assertThat(jdbc.queryForObject("SELECT password_change_required FROM users WHERE login_id = ?", Boolean.class, loginId)).isFalse();

        // 같은 제한 세션으로 다시 바꿀 수 없다.
        change(restricted, NEW_PASSWORD, "Another-pw4").andExpect(status().isUnauthorized());
        login(loginId, TEMPORARY).andExpect(status().isUnauthorized());
        login(loginId, NEW_PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isString());
    }

    @Test
    void concurrentChangesWithSameRestrictedSessionSucceedOnce() throws Exception {
        String loginId = admin(TEMPORARY, true);
        Cookie restricted = restrictedCookie(loginId);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String next = "Race-only-pw" + i;
            Callable<Integer> task = () -> {
                start.await();
                return change(restricted, TEMPORARY, next).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).containsOnlyOnce(204).filteredOn(s -> s != 204).containsOnly(401);
    }

    @Test
    void expiredRevokedOrLoggedOutRestrictedSessionsAreRejected() throws Exception {
        String expiredId = admin(TEMPORARY, true);
        Cookie expired = restrictedCookie(expiredId);
        jdbc.update("""
                UPDATE password_change_sessions SET issued_at = now() - interval '11 minutes', expires_at = now() - interval '1 minute'
                WHERE user_id = (SELECT id FROM users WHERE login_id = ?)""", expiredId);
        change(expired, TEMPORARY, NEW_PASSWORD).andExpect(status().isUnauthorized());

        // 초기화(auth_version 증가)·중지 시 제한 세션도 무효다.
        String resetId = admin(TEMPORARY, true);
        Cookie reset = restrictedCookie(resetId);
        jdbc.update("UPDATE users SET auth_version = auth_version + 1 WHERE login_id = ?", resetId);
        change(reset, TEMPORARY, NEW_PASSWORD).andExpect(status().isUnauthorized());

        String suspendedId = admin(TEMPORARY, true);
        Cookie suspended = restrictedCookie(suspendedId);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE login_id = ?", suspendedId);
        change(suspended, TEMPORARY, NEW_PASSWORD).andExpect(status().isUnauthorized());

        String logoutId = admin(TEMPORARY, true);
        Cookie loggedOut = restrictedCookie(logoutId);
        MvcResult logout = mvc.perform(csrf.apply(delete("/api/v1/auth/session")).cookie(loggedOut))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(logout.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(c -> assertThat(c).startsWith("__Host-password-change=").contains("Max-Age=0"));
        change(loggedOut, TEMPORARY, NEW_PASSWORD).andExpect(status().isUnauthorized());

        // 쿠키·Bearer가 모두 없으면 401, CSRF가 없으면 403.
        change(null, TEMPORARY, NEW_PASSWORD).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/password-changes").cookie(loggedOut).contentType("application/json")
                .content(changeBody(TEMPORARY, NEW_PASSWORD))).andExpect(status().isForbidden());
    }

    @Test
    void passwordChangeBlocksAllPastSessionsImmediately() throws Exception {
        String loginId = admin(PASSWORD, false);
        String a = JsonPath.read(login(loginId, PASSWORD).andReturn().getResponse().getContentAsString(), "$.accessToken");
        MvcResult deviceB = login(loginId, PASSWORD).andReturn();
        String b = JsonPath.read(deviceB.getResponse().getContentAsString(), "$.accessToken");
        Cookie bRefresh = deviceB.getResponse().getCookie("__Host-itda-refresh");

        // 일반 로그인 본인도 현재 비밀번호를 확인해 바꿀 수 있다.
        mvc.perform(csrf.apply(post("/api/v1/auth/password-changes")).header("Authorization", "Bearer " + a)
                .contentType("application/json").content(changeBody(PASSWORD, NEW_PASSWORD))).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + a)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + b)).andExpect(status().isUnauthorized());
        mvc.perform(csrf.apply(post("/api/v1/auth/token/refresh")).cookie(bRefresh)).andExpect(status().isUnauthorized());
        login(loginId, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongCurrentPasswordCountsTowardLockout() throws Exception {
        String loginId = admin(TEMPORARY, true);
        Cookie restricted = restrictedCookie(loginId);
        for (int i = 0; i < 4; i++) {
            change(restricted, "Wrong-pw-99", NEW_PASSWORD).andExpect(status().isUnauthorized());
        }
        change(restricted, "Wrong-pw-99", NEW_PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        change(restricted, TEMPORARY, NEW_PASSWORD).andExpect(status().isTooManyRequests());
        login(loginId, TEMPORARY).andExpect(status().isTooManyRequests());
    }

    private String admin(String password, boolean changeRequired) {
        String loginId = "test-admin-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
        UUID user = support.user("이○○", "FACILITY_MANAGER", support.department);
        jdbc.update("UPDATE users SET login_id = ?, password_hash = ?, password_change_required = ? WHERE id = ?", loginId,
                encoder.encode(password), changeRequired, user);
        return loginId;
    }

    private Cookie restrictedCookie(String loginId) throws Exception {
        Cookie cookie = login(loginId, TEMPORARY).andExpect(status().isOk()).andReturn().getResponse()
                .getCookie("__Host-password-change");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private ResultActions login(String loginId, String password) throws Exception {
        return mvc.perform(csrf.apply(post("/api/v1/auth/admin/sessions")).contentType("application/json")
                .content(loginBody(loginId, password)));
    }

    private ResultActions change(Cookie restricted, String current, String next) throws Exception {
        var request = csrf.apply(post("/api/v1/auth/password-changes")).contentType("application/json").content(changeBody(current, next));
        if (restricted != null) {
            request.cookie(restricted);
        }
        return mvc.perform(request);
    }

    private static String loginBody(String loginId, String password) {
        return """
                {"loginId":%s,"password":%s,"clientId":"admin-web","deviceLabel":"사무실 PC"}""".formatted(json(loginId), json(password));
    }

    private static String changeBody(String current, String next) {
        return """
                {"currentPassword":%s,"newPassword":%s}""".formatted(json(current), json(next));
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Instant lockedUntil(String loginId) {
        return jdbc.queryForObject("SELECT locked_until FROM users WHERE login_id = ?", java.time.OffsetDateTime.class, loginId)
                .toInstant();
    }

    private long authVersion(String loginId) {
        return jdbc.queryForObject("SELECT auth_version FROM users WHERE login_id = ?", Long.class, loginId);
    }
}
