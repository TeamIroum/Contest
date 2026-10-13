package kr.iroum.inspection.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.session.IssuedTokens;
import kr.iroum.inspection.auth.session.SessionService;

/** Access JWT·Redis 세션·Refresh 회전·CSRF·계정 상태 반영(업무 API 4.1절, D-15). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SessionTokenIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    AuthTestSupport support;
    UUID senior;

    @BeforeEach
    void setUp() {
        support = new AuthTestSupport(jdbc);
        senior = support.user("김○○", "SENIOR", support.agency);
    }

    @Test
    void bearerTokenGivesMeWithRolesScopesAndParticipant() throws Exception {
        IssuedTokens tokens = sessions.issue(senior, authVersion(senior), "pwa", "내 휴대폰");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(senior.toString()))
                .andExpect(jsonPath("$.roles[0]").value("SENIOR"))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.participantId").isNotEmpty())
                .andExpect(jsonPath("$.scopes[0].organizationType").value("AGENCY"));
        // 세션 절대 만료: SENIOR 전용 7일, Access는 15분
        assertThat(Duration.between(Instant.now(), tokens.refreshTokenExpiresAt())).isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7));
        assertThat(Duration.between(Instant.now(), tokens.accessTokenExpiresAt())).isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void adminRoleSessionsExpireAfterTwelveHours() {
        UUID manager = support.user("최○○", "ACTIVITY_MANAGER", support.agency);
        IssuedTokens tokens = sessions.issue(manager, authVersion(manager), "admin-web", "사무실 PC");
        assertThat(Duration.between(Instant.now(), tokens.refreshTokenExpiresAt()))
                .isBetween(Duration.ofHours(12).minusMinutes(1), Duration.ofHours(12));
    }

    @Test
    void refreshRotatesTokenWithoutExtendingAbsoluteExpiryAndRequiresCsrf() throws Exception {
        IssuedTokens tokens = sessions.issue(senior, authVersion(senior), "pwa", "내 휴대폰");
        Cookie refresh = new Cookie("__Host-itda-refresh", tokens.refreshToken());

        mvc.perform(post("/api/v1/auth/token/refresh").cookie(refresh))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        MvcResult result = mvc.perform(csrf.apply(post("/api/v1/auth/token/refresh")).cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.sessionId").value(tokens.sessionId().toString()))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();
        String setCookie = String.join("\n", result.getResponse().getHeaders("Set-Cookie"));
        assertThat(setCookie).contains("__Host-itda-refresh=", "HttpOnly", "Secure", "SameSite=Lax", "Path=/");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        String body = result.getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(body, "$.refreshTokenExpiresAt"))).isEqualTo(tokens.refreshTokenExpiresAt());
        String newRefresh = result.getResponse().getCookie("__Host-itda-refresh").getValue();
        assertThat(newRefresh).isNotEqualTo(tokens.refreshToken());
    }

    @Test
    void reusingOldRefreshTokenEndsTheWholeSession() throws Exception {
        IssuedTokens tokens = sessions.issue(senior, authVersion(senior), "pwa", "내 휴대폰");
        IssuedTokens rotated = sessions.refresh(tokens.refreshToken());
        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);

        mvc.perform(csrf.apply(post("/api/v1/auth/token/refresh")).cookie(new Cookie("__Host-itda-refresh", tokens.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        // 재사용 탐지 → 회전된 새 토큰과 Access도 쓸 수 없다
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + rotated.accessToken())).andExpect(status().isUnauthorized());
        mvc.perform(csrf.apply(post("/api/v1/auth/token/refresh")).cookie(new Cookie("__Host-itda-refresh", rotated.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshWithSameTokenSucceedsAtMostOnceAndEndsSession() throws Exception {
        IssuedTokens tokens = sessions.issue(senior, authVersion(senior), "pwa", "내 휴대폰");
        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        int parallel = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            Callable<MvcResult> call = () -> {
                start.await();
                return mvc.perform(csrf.apply(post("/api/v1/auth/token/refresh"))
                        .cookie(new Cookie("__Host-itda-refresh", tokens.refreshToken()))).andReturn();
            };
            futures.add(pool.submit(call));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        String winnerAccess = null;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get();
            statuses.add(result.getResponse().getStatus());
            if (result.getResponse().getStatus() == 200) {
                winnerAccess = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
            }
        }
        pool.shutdown();
        // 원자 회전: 같은 토큰으로는 한 번만 성공한다. 나머지는 재사용으로 판단되어 세션이 끝난다(업무 API 4.1절).
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 401).count()).isEqualTo(parallel - 1);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + winnerAccess)).andExpect(status().isUnauthorized());
    }

    @Test
    void suspendedAccountLosesExistingSessions() throws Exception {
        IssuedTokens tokens = sessions.issue(senior, authVersion(senior), "pwa", "내 휴대폰");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", senior);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isUnauthorized());
        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        mvc.perform(csrf.apply(post("/api/v1/auth/token/refresh")).cookie(new Cookie("__Host-itda-refresh", tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authVersionIncreaseInDatabaseBlocksOlderSessionsEvenIfRedisStillHasThem() throws Exception {
        IssuedTokens tokens = sessions.issue(senior, authVersion(senior), "pwa", "내 휴대폰");
        jdbc.update("UPDATE users SET auth_version = auth_version + 1 WHERE id = ?", senior);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isUnauthorized());
        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        mvc.perform(csrf.apply(post("/api/v1/auth/token/refresh")).cookie(new Cookie("__Host-itda-refresh", tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleRevocationAppliesOnNextRequest() throws Exception {
        UUID manager = support.user("최○○", "ACTIVITY_MANAGER", support.agency);
        support.grant(manager, "FACILITY_MANAGER", support.department);
        IssuedTokens tokens = sessions.issue(manager, authVersion(manager), "admin-web", "사무실 PC");
        jdbc.update("UPDATE user_role_scopes SET revoked_at = now() WHERE user_id = ? AND role = 'FACILITY_MANAGER'", manager);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("ACTIVITY_MANAGER"));
    }

    @Test
    void tamperedOrForeignTokensAreRejected() throws Exception {
        IssuedTokens tokens = sessions.issue(senior, authVersion(senior), "pwa", "내 휴대폰");
        String tampered = tokens.accessToken().substring(0, tokens.accessToken().length() - 2) + "xx";
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tampered)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void logoutAndDeviceSessionManagement() throws Exception {
        IssuedTokens phone = sessions.issue(senior, authVersion(senior), "pwa", "내 휴대폰");
        IssuedTokens tablet = sessions.issue(senior, authVersion(senior), "pwa", "집 태블릿");
        String bearer = "Bearer " + phone.accessToken();
        mvc.perform(get("/api/v1/me/sessions").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.current == true)].deviceLabel").value("내 휴대폰"))
                .andExpect(jsonPath("$.page").doesNotExist());

        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        // 다른 사용자의 세션 ID는 숨김(404)
        UUID other = support.user("박○○", "SENIOR", support.agency);
        IssuedTokens otherTokens = sessions.issue(other, authVersion(other), "pwa", "다른 휴대폰");
        mvc.perform(csrf.apply(delete("/api/v1/me/sessions/" + otherTokens.sessionId())).header("Authorization", bearer))
                .andExpect(status().isNotFound());
        // CSRF 없이 기기 로그아웃 불가
        mvc.perform(delete("/api/v1/me/sessions/" + tablet.sessionId()).header("Authorization", bearer)).andExpect(status().isForbidden());

        mvc.perform(csrf.apply(delete("/api/v1/me/sessions/" + tablet.sessionId())).header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tablet.accessToken())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", bearer)).andExpect(status().isOk());

        MvcResult logout = mvc.perform(csrf.apply(delete("/api/v1/auth/session")).header("Authorization", bearer))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(String.join("\n", logout.getResponse().getHeaders("Set-Cookie"))).contains("__Host-itda-refresh=;", "Max-Age=0");
        mvc.perform(get("/api/v1/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + otherTokens.accessToken())).andExpect(status().isOk());
    }

    private long authVersion(UUID user) {
        return jdbc.queryForObject("SELECT auth_version FROM users WHERE id = ?", Long.class, user);
    }
}
