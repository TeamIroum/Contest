package kr.iroum.inspection.support;

import java.util.UUID;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import kr.iroum.inspection.auth.AuthTestSupport;

/** Bearer·CSRF·Idempotency-Key를 붙여 업무 API를 부르는 테스트 도우미. */
public class ApiCaller {

    private final MockMvc mvc;
    private final PatrolFixture fixture;
    private final AuthTestSupport.Csrf csrf;

    public ApiCaller(MockMvc mvc, PatrolFixture fixture) throws Exception {
        this.mvc = mvc;
        this.fixture = fixture;
        this.csrf = AuthTestSupport.csrf(mvc);
    }

    public ResultActions get(UUID user, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", fixture.token(user)));
    }

    public ResultActions post(UUID user, String path, String json) throws Exception {
        return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path), user, json, null);
    }

    public ResultActions post(UUID user, String path, String json, UUID idempotencyKey) throws Exception {
        return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path), user, json, idempotencyKey);
    }

    public ResultActions put(UUID user, String path, String json) throws Exception {
        return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path), user, json, null);
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, UUID user, String json, UUID key) throws Exception {
        MockHttpServletRequestBuilder request = csrf.apply(builder).header("Authorization", fixture.token(user))
                .contentType("application/json").content(json);
        if (key != null) {
            request.header("Idempotency-Key", key.toString());
        }
        return mvc.perform(request);
    }
}
