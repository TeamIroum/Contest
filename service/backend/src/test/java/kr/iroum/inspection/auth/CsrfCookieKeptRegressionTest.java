package kr.iroum.inspection.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;

/**
 * 연결 시험 회귀: Bearer로 인증된 요청이 CSRF 쿠키를 지우면, 로그인 뒤 GET 한 번에 쿠키가 비어 다음 변경 요청(출석 요청 등)이 403이 된다.
 * Bearer 요청 뒤에도 쿠키가 남고 같은 토큰으로 변경 요청이 통과해야 한다. 값은 모두 테스트 전용 가상 값이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CsrfCookieKeptRegressionTest {

    static final String PASSWORD = "Keep-csrf-pw1";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Test
    void bearerRequestDoesNotClearCsrfCookie() throws Exception {
        AuthTestSupport support = new AuthTestSupport(jdbc);
        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        String loginId = support.admin(encoder, PASSWORD, false);
        String access = JsonPath.read(mvc.perform(csrf.apply(post("/api/v1/auth/admin/sessions")).contentType("application/json")
                .content("""
                        {"loginId":"%s","password":"%s","clientId":"admin-web","deviceLabel":"사무실 PC"}""".formatted(loginId, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");

        MockHttpServletResponse me = mvc.perform(csrf.apply(get("/api/v1/me")).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(me.getCookie("__Host-itda-csrf")).isNull();

        // 쿠키가 그대로이므로 같은 토큰으로 변경 요청(모든 기기 로그아웃, Bearer + CSRF)이 통과한다.
        mvc.perform(csrf.apply(delete("/api/v1/me/sessions")).header("Authorization", "Bearer " + access))
                .andExpect(status().isNoContent());
    }
}
