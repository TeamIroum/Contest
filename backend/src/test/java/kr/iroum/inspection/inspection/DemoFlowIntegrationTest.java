package kr.iroum.inspection.inspection;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.AuthTestSupport;
import kr.iroum.inspection.auth.otp.SmsSender;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.review.ReviewTaskOutbox;
import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.MockAiConfiguration;
import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;
import kr.iroum.inspection.support.PatrolFixture;

/**
 * 시연 흐름 API 통합 검증(05단계 REVIEW-04): 실제 로그인 API(어르신 인증번호·관리자 비밀번호) → 출석 요청·확정 → 개인 시작(담당자 출발 확인과 독립)
 * → 오늘 순회·동기화 → 지정 구도 사진 제출 → AI 품질·접수 → 담당자 검토 목록·상세·판정 → 복귀(AI 재판독 대기와 무관).
 * 화면·실기기·실제 AI 검증이 아니다(API 수준, 계약 기반 mock AI, SMS 발송은 기록용 대체).
 */
@SpringBootTest(properties = "inspection.jobs.scheduling-enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, MockAiConfiguration.class, DemoFlowIntegrationTest.RecordingSms.class})
class DemoFlowIntegrationTest {

    static final List<String[]> SENT = new CopyOnWriteArrayList<>();
    static final AtomicInteger IP = new AtomicInteger(50);

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingSms {
        @Bean
        SmsSender recordingSmsSender() {
            return (phone, code) -> SENT.add(new String[] {phone, code});
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Autowired
    PhoneCrypto phones;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    MockAiServer ai;

    @Autowired
    ReviewTaskOutbox reviews;

    @Autowired
    kr.iroum.inspection.support.MutableClock clock;

    AuthTestSupport.Csrf csrf;

    @Test
    void loginToReviewThroughBusinessApis() throws Exception {
        clock.set(FixedClockConfiguration.NOW);
        ai.reset();
        ai.requireKey(MockAiConfiguration.API_KEY);
        csrf = AuthTestSupport.csrf(mvc);
        PatrolFixture f = new PatrolFixture(jdbc, sessions, phones);
        PatrolFixture.Patrol p = f.patrol(2);
        UUID facilityManager = f.user("정○○");
        f.role(facilityManager, "FACILITY_MANAGER", f.department);

        // 1. 로그인: 어르신은 인증번호, 담당자는 비밀번호(토큰은 응답 본문, Refresh는 쿠키)
        String senior = seniorLogin(f.phone(p.users().get(0)));
        String partner = seniorLogin(f.phone(p.users().get(1)));
        String activity = adminLogin(f.manager);
        String facility = adminLogin(facilityManager);

        // 2. 오늘 순회(date 필수, KST) → 출석 요청 → 담당자 출석 확정
        String today = call(senior, get("/api/v1/me/patrols?date=2026-10-15")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(p.id().toString())).andReturn().getResponse().getContentAsString();
        assertMatches("TodayPatrols", today);
        call(senior, get("/api/v1/me/patrols")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/patrols?date=2026-10-15")).andExpect(status().isUnauthorized());
        call(senior, post("/api/v1/patrols/" + p.id() + "/attendance-requests").content(event("2026-10-15T00:55:00Z"))).andExpect(status().isOk());
        call(activity, put("/api/v1/admin/patrols/" + p.id() + "/attendance/" + p.participants().get(0))
                .content("{\"attendance\":\"PRESENT\",\"version\":" + version(p) + "}")).andExpect(status().isOk());

        // 3. 개인 시작은 담당자 출발 확인 없이 된다(독립). 출발 확인은 나중에 따로.
        call(senior, post("/api/v1/patrols/" + p.id() + "/activity-starts").content(event("2026-10-15T01:00:00Z"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS")).andExpect(jsonPath("$.departedAt").value("2026-10-15T01:00:00Z"));
        call(partner, post("/api/v1/patrols/" + p.id() + "/activity-starts").content(event("2026-10-15T01:01:00Z"))).andExpect(status().isOk());
        call(activity, post("/api/v1/admin/patrols/" + p.id() + "/departure-confirmations").header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"version\":" + version(p) + "}")).andExpect(status().isOk());
        call(senior, get("/api/v1/patrols/" + p.id() + "/sync-state")).andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"));

        // 4. 지정 구도 사진 제출 → AI 품질·접수(이상 의심) / 다른 구도는 AI 재판독 대기(202)
        ai.nextAnalyze(Reply.fixture("analyze_02_observe"), Reply.fixture("analyze_15_needs_review_retry"));
        String accepted = call(senior, photo(p, 1, "2026-10-15T01:10:00Z", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("RECEIVED")).andExpect(jsonPath("$.captureStatus").value("ACCEPTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(accepted).doesNotContain("covered", "grade", "severity");
        call(partner, photo(p, 2, "2026-10-15T01:11:00Z", 2)).andExpect(status().isAccepted());
        reviews.drain();

        // 5. 시설 담당자: 검토 목록 → 상세 → 판정(신규 문제)
        String list = call(facility, get("/api/v1/admin/review-tasks?status=OPEN")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String taskId = JsonPath.<List<String>>read(list, "$.items[?(@.triggerReason == 'ISSUE_SUSPECTED')].id").get(0);
        String detail = call(facility, get("/api/v1/admin/review-tasks/" + taskId)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String inspection = JsonPath.read(detail, "$.inspections[0].id");
        String analysis = JsonPath.read(detail, "$.inspections[0].analysis.analysisResultId");
        String media = JsonPath.read(detail, "$.inspections[0].media.id");
        call(facility, get("/api/v1/media/" + media + "/content")).andExpect(status().isOk());
        call(senior, get("/api/v1/media/" + media + "/content")).andExpect(status().isNotFound());
        call(facility, post("/api/v1/admin/review-tasks/" + taskId + "/decisions").header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"decision\":\"ISSUE_CONFIRMED\",\"followUpType\":null,\"analysisResultId\":\"" + analysis + "\",\"correctionId\":null,"
                        + "\"classificationVersion\":0,\"reason\":\"덮개 위 낙엽 확인\",\"version\":0,\"issueResolutions\":[{\"mode\":\"CREATE\","
                        + "\"inspectionId\":\"" + inspection + "\",\"existingIssueId\":null,\"defectCode\":\"covered\",\"summary\":\"빗물받이 덮임\"}]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.reviewTaskStatus").value("RESOLVED"));

        // 6. 복귀는 AI 재판독 대기와 무관하게 진행·종료된다.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM analysis_jobs j JOIN inspections i ON i.id = j.inspection_id JOIN capture_slots s ON s.id = i.capture_slot_id
                JOIN patrol_stops ps ON ps.id = s.patrol_stop_id WHERE ps.patrol_id = ? AND j.retry_owner = 'AI' AND j.status = 'PROCESSING'""",
                Integer.class, p.id())).isEqualTo(1);
        for (int i = 0; i < 2; i++) {
            call(i == 0 ? senior : partner, post("/api/v1/patrols/" + p.id() + "/return-requests").content(
                    "{\"returnReason\":\"NORMAL\",\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:20:00Z\"}"))
                    .andExpect(status().isOk());
        }
        for (int i = 0; i < 2; i++) {
            call(activity, post("/api/v1/admin/patrols/" + p.id() + "/return-confirmations").header("Idempotency-Key", UUID.randomUUID().toString())
                    .content("{\"participantId\":\"" + p.participants().get(i) + "\",\"returnedAt\":\"2026-10-15T01:25:00Z\",\"returnReason\":\"NORMAL\","
                            + "\"version\":" + version(p) + "}")).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT activity_status FROM patrols WHERE id = ?", String.class, p.id())).isEqualTo("ENDED");
    }

    private String seniorLogin(String phone) throws Exception {
        SENT.clear();
        String ip = "10.9.0." + IP.incrementAndGet() % 250;
        String challenge = mvc.perform(csrf.apply(post("/api/v1/auth/sms-challenges")).contentType("application/json")
                .content("{\"phoneNumber\":\"" + phone + "\"}").with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String body = mvc.perform(csrf.apply(post("/api/v1/auth/sms-verifications")).contentType("application/json")
                .content("{\"challengeId\":\"" + JsonPath.read(challenge, "$.challengeId") + "\",\"code\":\"" + SENT.get(0)[1]
                        + "\",\"clientId\":\"pwa\",\"deviceLabel\":\"시니어 앱\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String adminLogin(UUID user) throws Exception {
        String loginId = "demo-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("UPDATE users SET login_id = ?, password_hash = ?, password_change_required = false WHERE id = ?", loginId,
                encoder.encode("Test-only-pw1"), user);
        String body = mvc.perform(csrf.apply(post("/api/v1/auth/admin/sessions")).contentType("application/json")
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"Test-only-pw1\",\"clientId\":\"admin-web\",\"deviceLabel\":\"사무실\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(csrf.apply(request).header("Authorization", "Bearer " + token).contentType("application/json"));
    }

    private ResultActions call(String token, org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder request)
            throws Exception {
        request.cookie(csrf.cookie()).header("X-CSRF-Token", csrf.token()).header("Authorization", "Bearer " + token);
        return mvc.perform(request);
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder photo(PatrolFixture.Patrol p, int slot,
            String capturedAt, int seed) {
        var b = multipart("/api/v1/patrols/{p}/stops/{s}/inspections", p.id(), p.stops().get(1));
        b.file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", MockAiServer.jpeg(seed)));
        b.param("clientSubmissionId", UUID.randomUUID().toString()).param("captureSlotId", p.slots().get(slot).toString())
                .param("attemptId", UUID.randomUUID().toString()).param("capturedAt", capturedAt).param("maskingVersion", "web-mask-0.1.0")
                .param("deviceMasked", "true")
                .param("deviceMaskReport", "{\"faces\":0,\"plates\":0,\"vehicles\":0,\"detector\":\"yunet-web\",\"appVersion\":\"0.3.0\"}");
        return b;
    }

    private long version(PatrolFixture.Patrol p) {
        return jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, p.id());
    }

    private static String event(String at) {
        return "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"" + at + "\"}";
    }
}
