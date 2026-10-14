package kr.iroum.inspection.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.demo.DemoAccountBootstrap;
import kr.iroum.inspection.auth.demo.DemoProperties;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.phone.PhoneProperties;

/** local 프로필 시연 계정 준비: 관리자는 임시 비밀번호·변경 필수, 시니어는 가상 번호. 값은 테스트 전용이다. */
@SpringBootTest(properties = {"inspection.demo.admin-temporary-password=Demo-test-only1", "inspection.demo.otp-code=135790"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DemoAccountBootstrapTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void demoAdminsStartWithTemporaryPasswordAndSeniorsWithVirtualPhones() throws Exception {
        assertThat(jdbc.queryForList("SELECT login_id FROM users WHERE password_change_required ORDER BY login_id", String.class))
                // service/: 공원녹지과(demo-park)·사업 총괄(demo-program)·시스템 관리자(demo-admin)를 더했다
                .containsExactly("demo-activity", "demo-admin", "demo-park", "demo-program", "demo-road", "demo-sewer");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE phone_lookup_hmac IS NOT NULL", Long.class)).isEqualTo(2);

        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        mvc.perform(csrf.apply(post("/api/v1/auth/admin/sessions")).contentType("application/json").content("""
                {"loginId":"demo-road","password":"Demo-test-only1","clientId":"admin-web","deviceLabel":"시연 PC"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passwordChangeRequired").value(true));
        String challenge = com.jayway.jsonpath.JsonPath.read(mvc.perform(csrf.apply(post("/api/v1/auth/demo/otp-requests"))
                .contentType("application/json").content("{\"phoneNumber\":\"010-0000-0001\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.challengeId");
        mvc.perform(csrf.apply(post("/api/v1/auth/demo/otp-verifications")).contentType("application/json").content("""
                {"challengeId":"%s","code":"135790","clientId":"pwa","deviceLabel":"시연 휴대폰"}""".formatted(challenge)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.name").value("김○○"));
    }

    @Test
    void rejectsTemporaryPasswordBreakingRulesAndRealLookingPhones() {
        PhoneCrypto noKeys = new PhoneCrypto(new PhoneProperties(null, null));
        DemoProperties weakPassword = new DemoProperties(true, null, List.of(),
                List.of(new DemoProperties.Admin(UUID.randomUUID(), "demo-x")), "password");
        assertThatThrownBy(() -> new DemoAccountBootstrap(weakPassword, noKeys, jdbc, null).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("D-22");
    }
}
