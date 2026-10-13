package kr.iroum.inspection.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

/** 인증 테스트 공통: 가상 사업·계정 생성, CSRF 토큰 받기. 모든 값은 가상이다. */
public class AuthTestSupport {

    public record Csrf(Cookie cookie, String token) {
        public MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder builder) {
            return builder.cookie(cookie).header("X-CSRF-Token", token);
        }
    }

    public final JdbcTemplate jdbc;
    public final UUID program = UUID.randomUUID();
    public final UUID agency;
    public final UUID department;

    public AuthTestSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.update("INSERT INTO programs (id, name) VALUES (?, '가상 사업')", program);
        agency = org("AGENCY", null, "가상 복지관");
        department = org("DEPARTMENT", null, "가상 도로관리과");
    }

    public UUID org(String type, UUID parent, String name) {
        return jdbc.queryForObject("INSERT INTO organizations (program_id, parent_id, type, name) VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class, program, parent, type, name);
    }

    public UUID user(String name, String role, UUID organization) {
        UUID user = jdbc.queryForObject("INSERT INTO users (name) VALUES (?) RETURNING id", UUID.class, name);
        grant(user, role, organization);
        if ("SENIOR".equals(role)) {
            jdbc.update("INSERT INTO participants (program_id, user_id, organization_id) VALUES (?, ?, ?)", program, user, organization);
        }
        return user;
    }

    public void grant(UUID user, String role, UUID organization) {
        jdbc.update("INSERT INTO user_role_scopes (program_id, user_id, organization_id, role) VALUES (?, ?, ?, ?)",
                program, user, organization, role);
    }

    /** 비밀번호 로그인용 관리자(FACILITY_MANAGER). 비밀번호는 테스트 전용 가상 값이다. */
    public String admin(org.springframework.security.crypto.password.PasswordEncoder encoder, String password, boolean changeRequired) {
        UUID user = user("이○○", "FACILITY_MANAGER", department);
        String loginId = "test-admin-" + UUID.randomUUID().toString().substring(0, 13);
        jdbc.update("UPDATE users SET login_id = ?, password_hash = ?, password_change_required = ? WHERE id = ?", loginId,
                encoder.encode(password), changeRequired, user);
        return loginId;
    }

    public static Csrf csrf(MockMvc mvc) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        Cookie cookie = result.getResponse().getCookie("__Host-itda-csrf");
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.csrfToken");
        return new Csrf(cookie, token);
    }
}
