package kr.iroum.inspection.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.otp.SmsSender;
import kr.iroum.inspection.auth.phone.PhoneCrypto;

/**
 * 인증 리뷰 2번 회귀: 비밀번호 변경 의무(D-23)가 있는 계정은 어떤 로그인 경로로도 일반 업무 토큰을 받거나 쓰지 못한다.
 * SENIOR와 관리자 역할을 함께 가진 계정이 SMS로 로그인하는 경우를 재현한다. 값은 모두 테스트 전용 가상 값이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PasswordChangeRequiredRegressionTest.RecordingSms.class})
class PasswordChangeRequiredRegressionTest {

    static final String TEMPORARY = "Temp-only-pw2";
    static final String NEW_PASSWORD = "New-only-pw3";

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingSms {
        static final List<String> CODES = new CopyOnWriteArrayList<>();

        @Bean
        SmsSender recordingSmsSender() {
            return (phone, code) -> CODES.add(code);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    PhoneCrypto crypto;

    AuthTestSupport support;
    AuthTestSupport.Csrf csrf;

    @BeforeEach
    void setUp() throws Exception {
        support = new AuthTestSupport(jdbc);
        csrf = AuthTestSupport.csrf(mvc);
        RecordingSms.CODES.clear();
    }

    @Test
    void smsLoginCannotBypassPasswordChangeRequirement() throws Exception {
        String phone = "010-0000-" + (7000 + (int) (Math.random() * 999));
        String loginId = mixedAccount(phone, true);

        MvcResult result = smsLogin(phone).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                // 프론트는 message 문장이 아니라 details.reason으로 분기한다.
                .andExpect(jsonPath("$.details.reason").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getCookie("__Host-itda-refresh")).isNull();

        // 제한 세션의 허용 범위(관리자 로그인 → 비밀번호 변경)는 그대로 쓸 수 있고, 바꾼 뒤에는 SMS 로그인도 된다.
        Cookie restricted = mvc.perform(csrf.apply(post("/api/v1/auth/admin/sessions")).contentType("application/json")
                .content(loginBody(loginId, TEMPORARY))).andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true)).andReturn().getResponse()
                .getCookie("__Host-password-change");
        mvc.perform(csrf.apply(post("/api/v1/auth/password-changes")).cookie(restricted).contentType("application/json")
                .content("{\"currentPassword\":\"" + TEMPORARY + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        smsLogin(phone).andExpect(status().isOk()).andExpect(jsonPath("$.user.roles.length()").value(2));
    }

    @Test
    void csrfRejectionIsAlsoForbiddenButCarriesNoReason() throws Exception {
        // 같은 경로의 다른 403(CSRF 실패)은 details=null이라 reason으로 구분된다.
        mvc.perform(post("/api/v1/auth/sms-verifications").contentType("application/json").content("""
                {"challengeId":"c1000000-0000-4000-8000-000000000001","code":"123456","clientId":"pwa","deviceLabel":"내 휴대폰"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.details").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void existingSessionStopsWorkingOnceChangeBecomesRequired() throws Exception {
        String phone = "010-0000-" + (8000 + (int) (Math.random() * 999));
        String loginId = mixedAccount(phone, false);
        MvcResult login = smsLogin(phone).andExpect(status().isOk()).andReturn();
        String access = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        Cookie refresh = login.getResponse().getCookie("__Host-itda-refresh");

        // 변경 의무가 생기면(버전 증가 없이 표시만 바뀐 경우까지) 기존 일반 세션도 업무 API·갱신에 쓸 수 없다.
        jdbc.update("UPDATE users SET password_change_required = true WHERE login_id = ?", loginId);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
        mvc.perform(csrf.apply(post("/api/v1/auth/token/refresh")).cookie(refresh)).andExpect(status().isUnauthorized());
    }

    private String mixedAccount(String phone, boolean changeRequired) {
        String loginId = support.admin(encoder, TEMPORARY, changeRequired);
        UUID user = jdbc.queryForObject("SELECT id FROM users WHERE login_id = ?", UUID.class, loginId);
        support.grant(user, "SENIOR", support.agency);
        jdbc.update("INSERT INTO participants (program_id, user_id, organization_id) VALUES (?, ?, ?)", support.program, user,
                support.agency);
        String normalized = PhoneCrypto.normalize(phone);
        jdbc.update("UPDATE users SET phone_ciphertext = ?, phone_lookup_hmac = ? WHERE id = ?", crypto.encrypt(normalized),
                crypto.lookupHmac(normalized), user);
        return loginId;
    }

    private ResultActions smsLogin(String phone) throws Exception {
        RecordingSms.CODES.clear();
        String challenge = JsonPath.read(mvc.perform(csrf.apply(post("/api/v1/auth/sms-challenges")).contentType("application/json")
                .content("{\"phoneNumber\":\"" + phone + "\"}").with(r -> {
                    r.setRemoteAddr("10.7.0." + (int) (Math.random() * 250));
                    return r;
                })).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.challengeId");
        assertThat(RecordingSms.CODES).hasSize(1);
        return mvc.perform(csrf.apply(post("/api/v1/auth/sms-verifications")).contentType("application/json").content("""
                {"challengeId":"%s","code":"%s","clientId":"pwa","deviceLabel":"내 휴대폰"}""".formatted(challenge, RecordingSms.CODES.get(0))));
    }

    private static String loginBody(String loginId, String password) {
        return """
                {"loginId":"%s","password":"%s","clientId":"admin-web","deviceLabel":"사무실 PC"}""".formatted(loginId, password);
    }
}
