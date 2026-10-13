package kr.iroum.inspection.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.otp.SmsSender;
import kr.iroum.inspection.auth.phone.PhoneCrypto;

/**
 * 어르신 인증번호 로그인(D-20). 실제 운영값(10분·30초·5회·10분 제한, 번호 5회/IP 20회)을 그대로 쓰고,
 * 30초 재발송 대기는 Redis의 재발송 가능 시각을 과거로 바꿔 같은 코드 경로를 검증한다.
 */
@SpringBootTest(properties = {
        "inspection.demo.enabled=true",
        "inspection.demo.otp-code=246810",
        "inspection.demo.seniors[0].user-id=10000000-0000-4000-8000-0000000000d1",
        "inspection.demo.seniors[0].phone=010-0000-0091"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OtpLoginIntegrationTest.RecordingSms.class})
class OtpLoginIntegrationTest {

    static final UUID DEMO_SENIOR = UUID.fromString("10000000-0000-4000-8000-0000000000d1");
    static final AtomicInteger SEQ = new AtomicInteger(100);

    record Sent(String phone, String code) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingSms {
        static final List<Sent> SENT = new CopyOnWriteArrayList<>();

        @Bean
        SmsSender recordingSmsSender() {
            return (phone, code) -> SENT.add(new Sent(phone, code));
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PhoneCrypto crypto;

    @Autowired
    StringRedisTemplate redis;

    AuthTestSupport support;
    AuthTestSupport.Csrf csrf;
    String ip;

    @BeforeEach
    void setUp() throws Exception {
        support = new AuthTestSupport(jdbc);
        csrf = AuthTestSupport.csrf(mvc);
        // 테스트마다 다른 IP로 보내 IP별 발송 한도가 서로 섞이지 않게 한다.
        ip = "10.0.0." + SEQ.incrementAndGet() % 250;
        RecordingSms.SENT.clear();
    }

    @Test
    void registeredSeniorLogsInWithSmsCodeOnce() throws Exception {
        String phone = newPhone();
        UUID senior = seniorWithPhone(phone);
        MvcResult challenge = request("/api/v1/auth/sms-challenges", phone).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.challengeId").isString())
                .andReturn();
        String body = challenge.getResponse().getContentAsString();
        Instant expiresAt = Instant.parse(JsonPath.read(body, "$.expiresAt"));
        Instant resendAt = Instant.parse(JsonPath.read(body, "$.resendAvailableAt"));
        assertThat(java.time.Duration.between(resendAt, expiresAt)).isEqualTo(java.time.Duration.ofSeconds(570));
        assertThat(RecordingSms.SENT).singleElement().satisfies(s -> {
            assertThat(s.phone()).isEqualTo(phone.replace("-", ""));
            assertThat(s.code()).matches("[0-9]{6}");
        });
        String code = RecordingSms.SENT.get(0).code();
        String challengeId = JsonPath.read(body, "$.challengeId");

        MvcResult login = verify("/api/v1/auth/sms-verifications", challengeId, code).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(senior.toString()))
                .andExpect(jsonPath("$.user.roles[0]").value("SENIOR"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertThat(login.getResponse().getCookie("__Host-itda-refresh")).isNotNull();
        String access = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());

        // 소진된 챌린지는 같은 코드로 다시 쓸 수 없다.
        verify("/api/v1/auth/sms-verifications", challengeId, code).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void unregisteredNumberGetsSameShapeWithoutSendingAndCannotVerify() throws Exception {
        MvcResult result = request("/api/v1/auth/sms-challenges", newPhone()).andExpect(status().isAccepted()).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.Map<String, Object>>read(body, "$")).containsOnlyKeys("challengeId", "expiresAt",
                "resendAvailableAt");
        assertThat(RecordingSms.SENT).isEmpty();
        verify("/api/v1/auth/sms-verifications", JsonPath.read(body, "$.challengeId"), "123456")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void suspendedOrUnassignedAccountsAreTreatedAsUnregistered() throws Exception {
        String suspendedPhone = newPhone();
        UUID suspended = seniorWithPhone(suspendedPhone);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", suspended);
        request("/api/v1/auth/sms-challenges", suspendedPhone).andExpect(status().isAccepted());

        String adminPhone = newPhone();
        UUID admin = support.user("최○○", "FACILITY_MANAGER", support.department);
        setPhone(admin, adminPhone);
        request("/api/v1/auth/sms-challenges", adminPhone).andExpect(status().isAccepted());
        assertThat(RecordingSms.SENT).isEmpty();
    }

    @Test
    void accountSuspendedAfterCodeWasSentCannotLogIn() throws Exception {
        String phone = newPhone();
        UUID senior = seniorWithPhone(phone);
        String challengeId = challengeId(request("/api/v1/auth/sms-challenges", phone).andExpect(status().isAccepted()));
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", senior);
        verify("/api/v1/auth/sms-verifications", challengeId, RecordingSms.SENT.get(0).code())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resendWithin30SecondsIsLimitedAndResendKeepsCodeAndExpiry() throws Exception {
        String phone = newPhone();
        seniorWithPhone(phone);
        MvcResult first = request("/api/v1/auth/sms-challenges", phone).andExpect(status().isAccepted()).andReturn();
        request("/api/v1/auth/sms-challenges", phone).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").isNumber())
                .andExpect(header().exists("Retry-After"));

        String challengeId = challengeId(first);
        allowResend(challengeId);
        MvcResult second = request("/api/v1/auth/sms-challenges", phone).andExpect(status().isAccepted()).andReturn();
        assertThat(challengeId(second)).isEqualTo(challengeId);
        assertThat((String) JsonPath.read(second.getResponse().getContentAsString(), "$.expiresAt"))
                .isEqualTo(JsonPath.read(first.getResponse().getContentAsString(), "$.expiresAt"));
        assertThat(RecordingSms.SENT).hasSize(2);
        assertThat(RecordingSms.SENT.get(1).code()).isEqualTo(RecordingSms.SENT.get(0).code());
    }

    @Test
    void fiveWrongCodesInvalidateChallengeAndBlockNumberEvenAcrossResend() throws Exception {
        String phone = newPhone();
        seniorWithPhone(phone);
        String challengeId = challengeId(request("/api/v1/auth/sms-challenges", phone).andExpect(status().isAccepted()));
        String code = RecordingSms.SENT.get(0).code();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 3; i++) {
            verify("/api/v1/auth/sms-verifications", challengeId, wrong).andExpect(status().isUnauthorized());
        }
        // 재발송은 실패 횟수를 초기화하지 않는다.
        allowResend(challengeId);
        request("/api/v1/auth/sms-challenges", phone).andExpect(status().isAccepted());
        verify("/api/v1/auth/sms-verifications", challengeId, wrong).andExpect(status().isUnauthorized());
        verify("/api/v1/auth/sms-verifications", challengeId, wrong).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // 무효화된 챌린지는 맞는 코드로도 쓸 수 없고(코드 무효 401), 번호는 10분간 새 요청이 제한된다(429).
        verify("/api/v1/auth/sms-verifications", challengeId, code).andExpect(status().isUnauthorized());
        request("/api/v1/auth/sms-challenges", phone).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.details.retryAfterSeconds").value(org.hamcrest.Matchers.greaterThan(590)));
    }

    @Test
    void phoneSendLimitIsFivePerTenMinutes() throws Exception {
        String phone = newPhone();
        seniorWithPhone(phone);
        String challengeId = null;
        for (int i = 0; i < 5; i++) {
            if (challengeId != null) {
                allowResend(challengeId);
            }
            challengeId = challengeId(request("/api/v1/auth/sms-challenges", phone).andExpect(status().isAccepted()));
        }
        allowResend(challengeId);
        request("/api/v1/auth/sms-challenges", phone).andExpect(status().isTooManyRequests());
        assertThat(RecordingSms.SENT).hasSize(5);
    }

    @Test
    void ipSendLimitIsTwentyPerTenMinutes() throws Exception {
        for (int i = 0; i < 20; i++) {
            request("/api/v1/auth/sms-challenges", newPhone()).andExpect(status().isAccepted());
        }
        request("/api/v1/auth/sms-challenges", newPhone()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void demoCodeWorksOnlyForDemoSenior() throws Exception {
        jdbc.update("INSERT INTO users (id, name) VALUES (?, '김○○') ON CONFLICT DO NOTHING", DEMO_SENIOR);
        if (jdbc.queryForObject("SELECT count(*) FROM participants WHERE user_id = ?", Long.class, DEMO_SENIOR) == 0) {
            support.grant(DEMO_SENIOR, "SENIOR", support.agency);
            jdbc.update("INSERT INTO participants (program_id, user_id, organization_id) VALUES (?, ?, ?)", support.program,
                    DEMO_SENIOR, support.agency);
            setPhone(DEMO_SENIOR, "010-0000-0091");
        }
        String demoChallenge = challengeId(request("/api/v1/auth/demo/otp-requests", "010-0000-0091").andExpect(status().isAccepted()));
        verify("/api/v1/auth/demo/otp-verifications", demoChallenge, "246810").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(DEMO_SENIOR.toString()));

        // 시연 계정이 아닌 등록 시니어에게는 고정 코드가 통하지 않는다.
        String phone = newPhone();
        seniorWithPhone(phone);
        String other = challengeId(request("/api/v1/auth/demo/otp-requests", phone).andExpect(status().isAccepted()));
        verify("/api/v1/auth/demo/otp-verifications", other, "246810").andExpect(status().isUnauthorized());
        // 시연 챌린지를 SMS 검증 경로로 쓸 수 없다.
        verify("/api/v1/auth/sms-verifications", demoChallenge, "246810").andExpect(status().isUnauthorized());
        assertThat(RecordingSms.SENT).isEmpty();
    }

    @Test
    void requestsRequireCsrfAndValidPhone() throws Exception {
        mvc.perform(post("/api/v1/auth/sms-challenges").contentType("application/json")
                .content("{\"phoneNumber\":\"" + newPhone() + "\"}")).andExpect(status().isForbidden());
        request("/api/v1/auth/sms-challenges", "02-123-4567").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("phoneNumber"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("INVALID_FORMAT"));
        verify("/api/v1/auth/sms-verifications", UUID.randomUUID().toString(), "12345").andExpect(status().isBadRequest());
    }

    private ResultActions request(String path, String phone) throws Exception {
        return mvc.perform(csrf.apply(post(path)).contentType("application/json")
                .content("{\"phoneNumber\":\"" + phone + "\"}").with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                }));
    }

    private ResultActions verify(String path, String challengeId, String code) throws Exception {
        return mvc.perform(csrf.apply(post(path)).contentType("application/json").content("""
                {"challengeId":"%s","code":"%s","clientId":"pwa","deviceLabel":"내 휴대폰"}""".formatted(challengeId, code)));
    }

    private static String challengeId(ResultActions actions) throws Exception {
        return challengeId(actions.andReturn());
    }

    private static String challengeId(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.challengeId");
    }

    private void allowResend(String challengeId) {
        redis.opsForHash().put("otp:challenge:" + challengeId, "resendAvailableAt", Instant.now().minusSeconds(1).toString());
    }

    /** 가상 번호 대역(010-0000-xxxx)에서 테스트마다 다른 번호를 만든다. */
    private static String newPhone() {
        return "010-0000-%04d".formatted(SEQ.incrementAndGet());
    }

    private UUID seniorWithPhone(String phone) {
        UUID senior = support.user("김○○", "SENIOR", support.agency);
        setPhone(senior, phone);
        return senior;
    }

    private void setPhone(UUID user, String phone) {
        String normalized = PhoneCrypto.normalize(phone);
        jdbc.update("UPDATE users SET phone_ciphertext = ?, phone_lookup_hmac = ? WHERE id = ?", crypto.encrypt(normalized),
                crypto.lookupHmac(normalized), user);
    }
}
