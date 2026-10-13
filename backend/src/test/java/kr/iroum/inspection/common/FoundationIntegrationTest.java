package kr.iroum.inspection.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/** 01단계 기반: 빈 DB 기동, 상태 점검, 기본 거부 보안, 공통 오류 형식, 요청 ID. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FoundationIntegrationTest.ProbeController.class})
class FoundationIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublicAndCarriesRequestId() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void businessApiIsDeniedWithoutAuthenticationUsingContractErrorShape() throws Exception {
        mvc.perform(get("/api/v1/me").header("X-Request-Id", "req-client-0001"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "req-client-0001"))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value("req-client-0001"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void unsafeIncomingRequestIdIsReplaced() throws Exception {
        mvc.perform(get("/api/v1/me").header("X-Request-Id", "<script>"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.startsWith("req-")));
    }

    @Test
    @WithMockUser
    void apiExceptionUsesContractCodeStatusAndDetails() throws Exception {
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.details.currentVersion").value(4))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    @WithMockUser
    void validationErrorsListFieldsWithContractReasons() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \",\"version\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')].reason").value("REQUIRED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='version')].reason").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    @WithMockUser
    void unexpectedErrorHidesInternalMessage() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class ProbeController {

        record Body(@NotBlank String reason, @PositiveOrZero long version) {
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사용자가 내용을 변경했습니다.", Map.of("currentVersion", 4));
        }

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody Body body) {
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
