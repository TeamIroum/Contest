package kr.iroum.inspection.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.password.AdminPasswordService;
import kr.iroum.inspection.auth.session.IssuedTokens;
import kr.iroum.inspection.auth.session.SessionService;

/**
 * 인증 리뷰 1번 회귀: 비밀번호 확인과 세션 발급 사이에 비밀번호가 바뀌면, 이전 자격으로 확인된 요청은 유효한 세션을 얻으면 안 된다.
 * 컨트롤러의 확인 → 발급 사이에 다른 요청의 변경이 끼어드는 순서를 서비스 호출로 그대로 재현한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthVersionBindingRegressionTest {

    static final String PASSWORD = "Test-only-pw1";
    static final String NEW_PASSWORD = "New-only-pw3";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    AdminPasswordService passwords;

    @Autowired
    SessionService sessions;

    @Autowired
    kr.iroum.inspection.auth.otp.OtpChallengeService challenges;

    @Test
    void credentialVerifiedBeforePasswordChangeCannotYieldValidSession() throws Exception {
        AuthTestSupport support = new AuthTestSupport(jdbc);
        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        String loginId = support.admin(encoder, PASSWORD, false);
        String deviceA = JsonPath.read(mvc.perform(csrf.apply(post("/api/v1/auth/admin/sessions")).contentType("application/json")
                .content(loginBody(loginId, PASSWORD))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.accessToken");

        // ① 기존 비밀번호로 확인 성공
        AdminPasswordService.LoginResult verified = passwords.login(loginId, PASSWORD);
        assertThat(verified).isInstanceOf(AdminPasswordService.LoginResult.Success.class);

        // ② 다른 요청이 비밀번호를 바꿔 auth_version 증가
        mvc.perform(csrf.apply(post("/api/v1/auth/password-changes")).header("Authorization", "Bearer " + deviceA)
                .contentType("application/json").content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        // ③ ①의 요청이 세션 발급을 계속한다
        IssuedTokens stale;
        try {
            stale = issueAfterVerification(verified);
        } catch (kr.iroum.inspection.common.error.ApiException rejected) {
            assertThat(rejected.code()).isEqualTo(kr.iroum.inspection.common.error.ErrorCode.AUTH_REQUIRED);
            return;
        }
        // ④ 발급됐더라도 이전 자격의 세션은 쓸 수 없어야 한다
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + stale.accessToken())).andExpect(status().isUnauthorized());
        mvc.perform(csrf.apply(post("/api/v1/auth/token/refresh")).cookie(new Cookie("__Host-itda-refresh", stale.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otpChallengeIssuedBeforeAccountChangeCannotYieldSession() {
        // 인증번호 요청 뒤 검증 전에 계정이 바뀌면(예: 권한 변경·초기화로 auth_version 증가) 그 챌린지로 세션을 만들 수 없다.
        AuthTestSupport support = new AuthTestSupport(jdbc);
        java.util.UUID senior = support.user("김○○", "SENIOR", support.agency);
        long version = jdbc.queryForObject("SELECT auth_version FROM users WHERE id = ?", Long.class, senior);
        String phoneKey = java.util.UUID.randomUUID().toString().replace("-", "");
        java.util.concurrent.atomic.AtomicReference<String> sent = new java.util.concurrent.atomic.AtomicReference<>();
        var challenge = challenges.request(phoneKey, senior, version, "SMS", null, "10.9.9.9", sent::set);
        jdbc.update("UPDATE users SET auth_version = auth_version + 1 WHERE id = ?", senior);

        var verified = challenges.verify(challenge.challengeId(), sent.get(), "SMS");
        assertThat(verified.authVersion()).isEqualTo(version);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> sessions.issue(verified.userId(), verified.authVersion(), "pwa", "내 휴대폰"))
                .isInstanceOfSatisfying(kr.iroum.inspection.common.error.ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(kr.iroum.inspection.common.error.ErrorCode.AUTH_REQUIRED));
    }

    private IssuedTokens issueAfterVerification(AdminPasswordService.LoginResult verified) {
        AdminPasswordService.LoginResult.Success success = (AdminPasswordService.LoginResult.Success) verified;
        return sessions.issue(success.userId(), success.authVersion(), "admin-web", "사무실 PC");
    }

    private static String loginBody(String loginId, String password) {
        return """
                {"loginId":"%s","password":"%s","clientId":"admin-web","deviceLabel":"사무실 PC"}""".formatted(loginId, password);
    }
}
