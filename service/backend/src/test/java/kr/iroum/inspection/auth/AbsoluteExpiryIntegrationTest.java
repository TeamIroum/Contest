package kr.iroum.inspection.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.session.IssuedTokens;
import kr.iroum.inspection.auth.session.SessionService;

/**
 * 절대 만료: 회전해도 최초 로그인 만료를 넘지 못하고 Access 만료도 세션 만료를 넘지 않는다(D-15).
 * 실제 7일·12시간을 기다릴 수 없어 세션 기간만 짧게 바꾼 설정으로 같은 코드 경로를 검증한다.
 */
@SpringBootTest(properties = {"inspection.auth.senior-session-ttl=10s"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AbsoluteExpiryIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Test
    void sessionEndsAtAbsoluteExpiryEvenAfterRotation() throws Exception {
        AuthTestSupport support = new AuthTestSupport(jdbc);
        UUID senior = support.user("김○○", "SENIOR", support.agency);
        // 첫 Redis 연결·스크립트 적재 시간이 측정에 섞이지 않도록 한 번 데운다.
        sessions.refresh(sessions.issue(senior, authVersion(senior), "pwa", "준비").refreshToken());
        IssuedTokens first = sessions.issue(senior, authVersion(senior), "pwa", "내 휴대폰");
        assertThat(first.accessTokenExpiresAt()).isBeforeOrEqualTo(first.refreshTokenExpiresAt());

        IssuedTokens rotated = sessions.refresh(first.refreshToken());
        assertThat(rotated.refreshTokenExpiresAt()).isEqualTo(first.refreshTokenExpiresAt());
        assertThat(rotated.accessTokenExpiresAt()).isBeforeOrEqualTo(first.refreshTokenExpiresAt());

        // 절대 만료 시각이 지날 때까지 기다린다(시계 차이를 고려해 1초 여유).
        long waitMillis = java.time.Duration.between(java.time.Instant.now(), first.refreshTokenExpiresAt()).toMillis() + 1_000;
        Thread.sleep(Math.max(0, waitMillis));
        // service/: WSL2 등에서 바쁠 때 시계가 뒤로 다시 맞춰지면 한 번 잠든 것만으로는 만료가 안 지났을 수 있다. 실제로 지날 때까지 다시 확인한다
        while (java.time.Instant.now().isBefore(first.refreshTokenExpiresAt().plusSeconds(1))) {
            Thread.sleep(200);
        }
        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        mvc.perform(csrf.apply(post("/api/v1/auth/token/refresh")).cookie(new Cookie("__Host-itda-refresh", rotated.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + rotated.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    private long authVersion(UUID user) {
        return jdbc.queryForObject("SELECT auth_version FROM users WHERE id = ?", Long.class, user);
    }
}
