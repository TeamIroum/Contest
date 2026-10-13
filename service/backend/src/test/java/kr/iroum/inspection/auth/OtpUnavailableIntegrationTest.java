package kr.iroum.inspection.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import kr.iroum.inspection.TestcontainersConfiguration;

/** 기본(운영) 설정: 시연 경로는 404, SMS 공급자가 없으면 SMS 경로는 503. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OtpUnavailableIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void demoPathsAreHiddenAndSmsIsUnavailableWithoutProvider() throws Exception {
        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        String phone = "{\"phoneNumber\":\"010-0000-0001\"}";
        String verification = """
                {"challengeId":"c1000000-0000-4000-8000-000000000001","code":"123456","clientId":"pwa","deviceLabel":"휴대폰"}""";
        mvc.perform(csrf.apply(post("/api/v1/auth/demo/otp-requests")).contentType("application/json").content(phone))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(csrf.apply(post("/api/v1/auth/demo/otp-verifications")).contentType("application/json").content(verification))
                .andExpect(status().isNotFound());
        mvc.perform(csrf.apply(post("/api/v1/auth/sms-challenges")).contentType("application/json").content(phone))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        mvc.perform(csrf.apply(post("/api/v1/auth/sms-verifications")).contentType("application/json").content(verification))
                .andExpect(status().isServiceUnavailable());
    }
}
