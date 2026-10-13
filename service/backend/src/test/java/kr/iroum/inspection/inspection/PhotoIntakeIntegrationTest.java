package kr.iroum.inspection.inspection;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.AuthTestSupport;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.review.ReviewTaskOutbox;
import kr.iroum.inspection.support.ApiCaller;
import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.MockAiConfiguration;
import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;
import kr.iroum.inspection.support.MutableClock;
import kr.iroum.inspection.support.PatrolFixture;

/**
 * 사진 접수(업무 API 6.3·7·15절)를 실제 DB와 계약 기반 mock AI로 검증한다. 실제 AI 서버·기기 마스킹 검증이 아니다.
 */
class PhotoIntakeIntegrationTest extends PhotoTestBase {

    @Test
    void acceptedPhotoIsStoredAsServerCheckedCopyAndReplaysWithoutCallingAiAgain() throws Exception {
        jdbc.update("UPDATE facilities SET location_tags_json = '[\"school_route\",\"roadside\"]' WHERE id = ?", f.facilities.get(0));
        UUID submission = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        String body = submit(0, 0, submission, attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.captureStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.nextAction").value("NEXT_STOP"))
                .andExpect(jsonPath("$.nextStopId").value(p.stops().get(1).toString()))
                .andExpect(jsonPath("$.nextCaptureSlotId").value(p.slots().get(1).toString()))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.retakesRemaining").value(2))
                .andExpect(jsonPath("$.message").value("사진이 잘 찍혔어요. 다음 장소로 이동해 주세요."))
                .andReturn().getResponse().getContentAsString();
        assertMatches("PhotoSubmitResult", body);
        assertThat(body).doesNotContain("grade", "severity", "senior_message", "/images/");
        UUID inspection = UUID.fromString(JsonPath.read(body, "$.inspectionId"));

        // AI로 보낸 값: 가명 참여자 ID, 시설 대장 태그, snake_case 보고. 위경도는 보내지 않는다.
        MockAiServer.AnalyzeRequest sent = ai.analyzeRequests().get(0);
        assertThat(sent.fields()).containsEntry("participant_id", p.participants().get(0).toString())
                .containsEntry("location_tags", "school_route,roadside").containsEntry("captured_at", "2026-10-15T01:20:00Z")
                .doesNotContainKeys("lat", "lon");
        assertThat(sent.fields().get("photo_id")).startsWith("insp_");
        assertThat(sent.fields().get("device_mask_report")).contains("\"app_version\":\"0.3.0\"");

        // 업무 저장소에는 서버 검사 완료본(AI가 가린 사진)만 있다.
        String key = jdbc.queryForObject("SELECT m.object_key FROM inspections i JOIN media m ON m.id = i.approved_media_id WHERE i.id = ?",
                String.class, inspection);
        byte[] stored = Files.readAllBytes(paths.media().resolve("inspection-media").resolve(key));
        assertThat(stored).isNotEqualTo(MockAiServer.jpeg(1));
        assertThat(jdbc.queryForObject("SELECT privacy_status || ':' || purpose FROM media m JOIN inspections i ON i.media_id = m.id WHERE i.id = ?",
                String.class, inspection)).isEqualTo("APPROVED:INSPECTION");
        assertThat(jdbc.queryForObject("""
                SELECT r.result_json::text FROM analysis_results r JOIN analysis_jobs j ON j.id = r.analysis_job_id WHERE j.inspection_id = ?""",
                String.class, inspection)).doesNotContain("masked_image_url").contains("\"status\": \"ok\"");
        assertThat(jdbc.queryForObject("SELECT resolution FROM capture_slots WHERE id = ?", String.class, p.slots().get(0))).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT visit_status FROM patrol_stops WHERE id = ?", String.class, p.stops().get(0))).isEqualTo("CAPTURED");
        // 이상 미검출 + 표본 비율 0이면 표본 검토는 없다. AI 시설 종류(drain)가 대장(SIDEWALK)과 달라 FACILITY_MISMATCH 검토만 생기고
        // 시설 대장은 바꾸지 않는다.
        reviews.drain();
        assertThat(tasks()).containsExactly("INSPECTION:FACILITY_MISMATCH:1");
        assertThat(jdbc.queryForObject("SELECT type FROM facilities WHERE id = ?", String.class, f.facilities.get(0))).isEqualTo("SIDEWALK");

        // 같은 제출 재전송은 AI를 다시 부르지 않고 현재 결과를 duplicate=true로 재현한다.
        int calls = ai.analyzeCalls();
        submit(0, 0, submission, attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.inspectionId").value(inspection.toString())).andExpect(jsonPath("$.duplicate").value(true));
        assertThat(ai.analyzeCalls()).isEqualTo(calls);
        // 같은 제출 ID에 다른 파일, 같은 시도 ID를 새 제출 ID로 재사용하면 충돌.
        submit(0, 0, submission, attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBMISSION_CONFLICT"))
                .andExpect(jsonPath("$.details.existingInspectionId").value(inspection.toString()));
        submit(0, 0, UUID.randomUUID(), attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBMISSION_CONFLICT"));

        // 다른 조원의 새 사진은 채택된 기존 시도를 duplicate=true로 돌려받는다(D-18). 새 시도·AI 호출 없음.
        UUID other = UUID.randomUUID();
        String dup = submit(1, 0, other, UUID.randomUUID(), "2026-10-15T01:21:00Z", MockAiServer.jpeg(3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.inspectionId").value(inspection.toString()))
                .andExpect(jsonPath("$.clientSubmissionId").value(other.toString()))
                .andExpect(jsonPath("$.message").value("같이 계신 분의 사진이 이미 접수됐어요. 다음 장소로 이동해 주세요."))
                .andReturn().getResponse().getContentAsString();
        assertMatches("PhotoSubmitResult", dup);
        assertThat(ai.analyzeCalls()).isEqualTo(calls);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inspections WHERE capture_slot_id = ?", Integer.class, p.slots().get(0))).isEqualTo(1);

        String statusBody = api.get(p.users().get(1), "/api/v1/inspections/" + inspection + "/capture-status").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertMatches("CaptureResult", statusBody);
    }

    @Test
    void qualityRetakesCountOnlyQualityFailuresAndThirdFailureHandsOverToReview() throws Exception {
        ai.nextAnalyze(Reply.fixture("analyze_09_retake_dark"), Reply.fixture("analyze_10_error_unreadable"),
                Reply.fixture("analyze_08_retake_frame"), Reply.error(503, "UNAVAILABLE"), Reply.fixture("analyze_09_retake_dark"));
        String first = submit(0, 1, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.receiptStatus").value("NO_MEDIA"))
                .andExpect(jsonPath("$.captureStatus").value("RETAKE_REQUIRED")).andExpect(jsonPath("$.retakeReason").value("DARK"))
                .andExpect(jsonPath("$.nextAction").value("RETAKE")).andExpect(jsonPath("$.nextCaptureSlotId").value(p.slots().get(1).toString()))
                .andExpect(jsonPath("$.qualityFailureCount").value(1)).andExpect(jsonPath("$.retakesRemaining").value(2))
                .andReturn().getResponse().getContentAsString();
        assertMatches("PhotoSubmitResult", first);

        // AI가 사진을 처리하지 못함: 422, 저장 없음, 품질 횟수 그대로. 같은 ID 재전송은 같은 422를 AI 호출 없이 재현.
        UUID failed = UUID.randomUUID();
        UUID failedAttempt = UUID.randomUUID();
        String error = submit(0, 1, failed, failedAttempt, "2026-10-15T01:21:00Z", MockAiServer.jpeg(2))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IMAGE_PROCESSING_FAILED"))
                .andExpect(jsonPath("$.details.receiptStatus").value("NO_MEDIA"))
                .andExpect(jsonPath("$.details.captureStatus").value("TECHNICAL_FAILED"))
                .andExpect(jsonPath("$.details.nextAction").value("RETAKE")).andExpect(jsonPath("$.details.qualityFailureCount").value(1))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorImageProcessingFailed", error);
        int calls = ai.analyzeCalls();
        submit(0, 1, failed, failedAttempt, "2026-10-15T01:21:00Z", MockAiServer.jpeg(2)).andExpect(status().isUnprocessableContent());
        assertThat(ai.analyzeCalls()).isEqualTo(calls);

        submit(0, 1, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:22:00Z", MockAiServer.jpeg(3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("RECEIVED")).andExpect(jsonPath("$.retakeReason").value("OUT_OF_FRAME"))
                .andExpect(jsonPath("$.qualityFailureCount").value(2)).andExpect(jsonPath("$.retakesRemaining").value(1));
        // AI 연결 실패는 품질 횟수에 넣지 않는다.
        submit(0, 1, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:23:00Z", MockAiServer.jpeg(4)).andExpect(status().isServiceUnavailable());
        String third = submit(1, 1, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:24:00Z", MockAiServer.jpeg(5))
                .andExpect(status().isOk()).andExpect(jsonPath("$.captureStatus").value("REVIEW_REQUESTED"))
                .andExpect(jsonPath("$.receiptStatus").value("NO_MEDIA")).andExpect(jsonPath("$.qualityFailureCount").value(3))
                .andExpect(jsonPath("$.retakesRemaining").value(0)).andExpect(jsonPath("$.nextAction").value("NEXT_CAPTURE"))
                .andExpect(jsonPath("$.nextCaptureSlotId").value(p.slots().get(2).toString()))
                .andExpect(jsonPath("$.message").value("담당자에게 확인을 요청했어요. 다음 사진으로 넘어갈게요."))
                .andReturn().getResponse().getContentAsString();
        assertMatches("PhotoSubmitResult", third);
        assertThat(jdbc.queryForObject("SELECT resolution || ':' || quality_failure_count FROM capture_slots WHERE id = ?", String.class,
                p.slots().get(1))).isEqualTo("REVIEW_REQUESTED:3");
        reviews.drain();
        assertThat(tasks()).containsExactly("CAPTURE_FAILURE:QUALITY_RETRY_EXHAUSTED:3");
        // 인계된 구도에는 새 현장 시도를 받지 않는다.
        submit(0, 1, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:25:00Z", MockAiServer.jpeg(6)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_RESOLVED")).andExpect(jsonPath("$.details.resolution").value("REVIEW_REQUESTED"));
        // 시도 번호는 실제 새 촬영마다 늘어난다(같은 ID 재전송 제외): 1·2·3·4·5.
        assertThat(jdbc.queryForList("SELECT attempt_no FROM inspections WHERE capture_slot_id = ? ORDER BY attempt_no", Integer.class,
                p.slots().get(1))).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void aiReviewAndIssueResultsCreateReviewTasksWithoutChangingQualityCount() throws Exception {
        ai.nextAnalyze(Reply.fixture("analyze_02_observe"), Reply.fixture("analyze_07_needs_review_unknown"),
                Reply.fixture("analyze_15_needs_review_retry"));
        String issue = submit(0, 1, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.captureStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.nextAction").value("NEXT_CAPTURE")).andExpect(jsonPath("$.nextCaptureSlotId").value(p.slots().get(2).toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(issue).doesNotContain("낙엽", "covered");
        // 시설 안 보임·목록 외 시설은 품질 실패가 아니라 담당자 검토(retakeReason null, 횟수 그대로).
        String review = submit(1, 2, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:21:00Z", MockAiServer.jpeg(2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.receiptStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.captureStatus").value("REVIEW_REQUESTED")).andExpect(jsonPath("$.retakeReason").isEmpty())
                .andExpect(jsonPath("$.qualityFailureCount").value(0)).andExpect(jsonPath("$.nextAction").value("NEXT_STOP"))
                .andExpect(jsonPath("$.message").value("담당자가 사진을 한 번 더 볼게요. 다음 장소로 가셔도 돼요."))
                .andReturn().getResponse().getContentAsString();
        assertMatches("PhotoSubmitResult", review);
        assertThat(jdbc.queryForObject("SELECT visit_status FROM patrol_stops WHERE id = ?", String.class, p.stops().get(1)))
                .isEqualTo("REVIEW_REQUESTED");
        // AI 재판독 대기(retry.pending): 사진은 영속 접수, 후속 작업이 남아 202.
        submit(0, 3, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:22:00Z", MockAiServer.jpeg(3)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.receiptStatus").value("RECEIVED")).andExpect(jsonPath("$.captureStatus").value("REVIEW_REQUESTED"));
        assertThat(jdbc.queryForObject("""
                SELECT j.retry_owner || ':' || j.status FROM analysis_jobs j JOIN inspections i ON i.id = j.inspection_id
                WHERE i.capture_slot_id = ?""", String.class, p.slots().get(3))).isEqualTo("AI:PROCESSING");
        reviews.drain();
        reviews.drain();
        assertThat(tasks()).containsExactlyInAnyOrder("INSPECTION:ISSUE_SUSPECTED:1", "INSPECTION:AI_NEEDS_REVIEW:1",
                "INSPECTION:AI_NEEDS_REVIEW:1");
    }

    @Test
    void aiUnavailableKeepsPhotoOnDeviceAndSameIdResendRecoversWithSameAiCallId() throws Exception {
        ai.nextAnalyze(Reply.error(503, "UNAVAILABLE"), Reply.error(400, "INVALID_PHOTO_ID"), Reply.drop());
        UUID submission = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            String body = submit(0, 0, submission, attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ANALYSIS_UNAVAILABLE"))
                    .andExpect(jsonPath("$.details.receiptStatus").value("RETRY_REQUIRED"))
                    .andReturn().getResponse().getContentAsString();
            assertMatches("ErrorAnalysisUnavailable", body);
            assertThat(body).doesNotContain("INVALID_PHOTO_ID", "\"UNAVAILABLE\"");
        }
        String inspection = jdbc.queryForObject("SELECT id::text FROM inspections WHERE client_submission_id = ?", String.class, submission);
        assertThat(jdbc.queryForObject("SELECT active_inspection_id IS NULL AND quality_failure_count = 0 FROM capture_slots WHERE id = ?",
                Boolean.class, p.slots().get(0))).isTrue();
        String status = api.get(p.users().get(0), "/api/v1/inspections/" + inspection + "/capture-status").andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("RETRY_REQUIRED")).andExpect(jsonPath("$.nextAction").value("RETRY"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("CaptureResult", status);
        // 같은 시도·같은 파일 재전송은 같은 AI 호출 ID로 다시 보낸다.
        submit(0, 0, submission, attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.inspectionId").value(inspection)).andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.captureStatus").value("ACCEPTED"));
        assertThat(ai.analyzeRequests().stream().map(r -> r.fields().get("photo_id")).distinct()).hasSize(1);
    }

    @Test
    void concurrentSubmissionsToOneSlotProcessOnlyOneAndDuplicateFollowsAcceptance() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ai.holdAnalyze(gate);
        UUID first = UUID.randomUUID();
        UUID firstAttempt = UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> running = pool.submit(() -> submit(0, 0, first, firstAttempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1))
                    .andReturn());
            waitForAnalyzeCalls(1);
            // 처리 중: 같은 제출은 SUBMISSION_PROCESSING, 다른 조원은 SLOT_PROCESSING. 구도 상태는 처리 중으로 보인다.
            submit(0, 0, first, firstAttempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SUBMISSION_PROCESSING"));
            String processing = submit(1, 0, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:20:30Z", MockAiServer.jpeg(2))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_PROCESSING"))
                    .andReturn().getResponse().getContentAsString();
            assertMatches("ErrorSlotProcessing", processing);
            api.get(p.users().get(1), "/api/v1/patrols/" + p.id() + "/sync-state")
                    .andExpect(jsonPath("$.stops[0].captureSlots[0].receiptStatus").value("RECEIVING"))
                    .andExpect(jsonPath("$.stops[0].captureSlots[0].captureStatus").value("PROCESSING"));
            gate.countDown();
            assertThat(running.get().getResponse().getStatus()).isEqualTo(200);
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
        ai.holdAnalyze(null);
        assertThat(ai.analyzeCalls()).isEqualTo(1);
        submit(1, 0, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:20:30Z", MockAiServer.jpeg(2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.captureStatus").value("ACCEPTED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inspections WHERE capture_slot_id = ? AND capture_status = 'ACCEPTED'",
                Integer.class, p.slots().get(0))).isEqualTo(1);
    }

    @Test
    void expiredIngestionLeaseIsRecoveredAndLateResultIsDiscarded() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ai.holdAnalyze(gate);
        UUID first = UUID.randomUUID();
        UUID firstAttempt = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> stuck = pool.submit(() -> submit(0, 0, first, firstAttempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1))
                    .andReturn());
            waitForAnalyzeCalls(1);
            clock.set(FixedClockConfiguration.NOW.plusSeconds(180));
            // 리스가 끝난 처리 중 시도는 다른 조원 제출 때 RETRY_REQUIRED로 정리되고 새 시도가 구도를 잡는다.
            Future<MvcResult> second = pool.submit(() -> submit(1, 0, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:21:00Z",
                    MockAiServer.jpeg(2)).andReturn());
            waitForAnalyzeCalls(2);
            gate.countDown();
            MvcResult late = stuck.get();
            assertThat(late.getResponse().getStatus()).isEqualTo(503);
            assertThat(second.get().getResponse().getStatus()).isEqualTo(200);
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
        ai.holdAnalyze(null);
        assertThat(jdbc.queryForObject("SELECT receipt_status FROM inspections WHERE client_submission_id = ?", String.class, first))
                .isEqualTo("RETRY_REQUIRED");
        // 늦게 끝난 첫 시도의 저장 사본은 지워지고 media 행도 없다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media m JOIN inspections i ON i.media_id = m.id WHERE i.capture_slot_id = ?",
                Integer.class, p.slots().get(0))).isEqualTo(1);
        try (Stream<Path> files = Files.walk(paths.media().resolve("inspection-media").resolve(f.program.toString()))) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
        }
        // 첫 조원이 다시 보내면 이미 채택된 사진이 있어 duplicate=true(D-18).
        submit(0, 0, first, firstAttempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    void deadlineReturnAndWaitingRulesUseServerAndCaptureTimes() throws Exception {
        UUID submission = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        submit(0, 0, submission, attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk());
        // 조원 1의 실제 복귀 01:25. 그 뒤 촬영은 422, 그 전 촬영은 늦게 와도 접수하고 전송 완료 안내.
        api.post(p.users().get(1), "/api/v1/patrols/" + p.id() + "/activity-starts",
                "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:00:00Z\"}").andExpect(status().isOk());
        long version = jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, p.id());
        api.post(f.manager, "/api/v1/admin/patrols/" + p.id() + "/return-confirmations", "{\"participantId\":\"" + p.participants().get(1)
                + "\",\"returnedAt\":\"2026-10-15T01:25:00Z\",\"returnReason\":\"NORMAL\",\"version\":" + version + "}", UUID.randomUUID())
                .andExpect(status().isOk());
        String after = submit(1, 1, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:26:00Z", MockAiServer.jpeg(2))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PHOTO_AFTER_RETURN"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorPhotoAfterReturn", after);
        submit(1, 1, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:24:00Z", MockAiServer.jpeg(3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.captureStatus").value("ACCEPTED")).andExpect(jsonPath("$.nextAction").value("RETURN_TO_CENTER"))
                .andExpect(jsonPath("$.message").value("사진이 전송됐어요. 활동은 이미 끝났으니 따로 하실 일은 없어요."));

        // 조원 1 복귀 확인으로 조원 0이 혼자 남아 대기가 열렸다(서버 시각 01:30). 대기 구간 안에서 촬영한 사진은 받지 않는다(D-35).
        assertThat(jdbc.queryForObject("SELECT started_at FROM patrol_solo_waits WHERE patrol_id = ? AND ended_at IS NULL",
                java.time.OffsetDateTime.class, p.id()).toInstant()).isEqualTo(FixedClockConfiguration.NOW);
        submit(0, 2, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:30:30Z", MockAiServer.jpeg(4)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT")).andExpect(jsonPath("$.details.reason").value("SOLO_WAITING"));

        // 전송 기한(활동일 다음 날 00:00 KST) 이후 새 사진은 409, 기한 전 접수의 재현은 그대로.
        clock.set(Instant.parse("2026-10-15T15:00:00Z"));
        String deadline = submit(0, 3, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:21:00Z", MockAiServer.jpeg(5))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYNC_DEADLINE_EXCEEDED"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorSyncDeadlineExceeded", deadline);
        submit(0, 0, submission, attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    void lateTechnicalFailureAfterEndHandsOverToReviewAndFillsAutoNotVisitedStop() throws Exception {
        endPatrol();
        assertThat(jdbc.queryForObject("SELECT visit_status || ':' || not_visited_reason FROM patrol_stops WHERE id = ?", String.class,
                p.stops().get(2))).isEqualTo("NOT_VISITED:TEAM_ENDED");
        ai.nextAnalyze(Reply.fixture("analyze_10_error_unreadable"), Reply.fixture("analyze_01_normal"));
        String body = submit(0, 0, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:10:00Z", MockAiServer.jpeg(1))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.captureStatus").value("REVIEW_REQUESTED"))
                .andExpect(jsonPath("$.details.nextAction").value("RETURN_TO_CENTER"))
                .andExpect(jsonPath("$.message").value("사진을 처리하지 못했어요. 다시 찍으러 가지 않으셔도 되고, 담당자가 확인할게요."))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorImageProcessingFailed", body);
        assertThat(jdbc.queryForObject("SELECT visit_status FROM patrol_stops WHERE id = ?", String.class, p.stops().get(0)))
                .isEqualTo("REVIEW_REQUESTED");
        // 자동 미방문 장소는 적법한 늦은 사진으로 보완하되 활동 상태·종료 기록은 바꾸지 않는다(D-27).
        String endedAt = jdbc.queryForObject("SELECT finished_at::text FROM patrols WHERE id = ?", String.class, p.id());
        submit(1, 3, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:11:00Z", MockAiServer.jpeg(2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextAction").value("RETURN_TO_CENTER"));
        assertThat(jdbc.queryForObject("SELECT visit_status || ':' || COALESCE(not_visited_reason, '-') FROM patrol_stops WHERE id = ?",
                String.class, p.stops().get(2))).isEqualTo("CAPTURED:-");
        assertThat(jdbc.queryForObject("SELECT activity_status || ':' || finished_at::text FROM patrols WHERE id = ?", String.class, p.id()))
                .isEqualTo("ENDED:" + endedAt);
        reviews.drain();
        // 장소 ③(BENCH)에 AI가 drain으로 본 사진은 대장 불일치 검토도 남는다.
        assertThat(tasks()).containsExactlyInAnyOrder("CAPTURE_FAILURE:TECHNICAL_FAILURE:1", "INSPECTION:FACILITY_MISMATCH:1");
    }

    @Test
    void skippedSlotAcceptsOnlyPhotosTakenBeforeTheSkipRegardlessOfArrivalOrder() throws Exception {
        // L2: 건너뛰기(01:25 발생) → 그 전에 찍은 사진 → 접수·채택, 장소 CAPTURED, 건너뛰기 이력 유지.
        skip(1, 0, "2026-10-15T01:25:00Z").andExpect(status().isOk()).andExpect(jsonPath("$.visitStatus").value("SKIPPED"));
        submit(0, 0, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:24:59Z", MockAiServer.jpeg(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.captureStatus").value("ACCEPTED"));
        assertThat(stop(0)).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("SELECT skipped_by_event_id IS NOT NULL FROM capture_slots WHERE id = ?", Boolean.class, p.slots().get(0)))
                .isTrue();
        // L3: 건너뛰기 이후(같은 시각 포함) 촬영은 409 SLOT_RESOLVED.
        skip(1, 2, "2026-10-15T01:25:00Z").andExpect(status().isOk());
        String resolved = submit(0, 3, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:25:00Z", MockAiServer.jpeg(2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_RESOLVED"))
                .andExpect(jsonPath("$.details.resolution").value("SKIPPED")).andReturn().getResponse().getContentAsString();
        assertMatches("ErrorSlotResolved", resolved);
        // L7(S4): 건너뛰기 전 촬영이 품질 실패면 재촬영 대신 검토 인계.
        ai.nextAnalyze(Reply.fixture("analyze_09_retake_dark"));
        submit(0, 3, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:24:00Z", MockAiServer.jpeg(3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.captureStatus").value("REVIEW_REQUESTED")).andExpect(jsonPath("$.qualityFailureCount").value(1))
                .andExpect(jsonPath("$.nextAction").value("NEXT_STOP"));
        assertThat(stop(2)).isEqualTo("REVIEW_REQUESTED");
        // L12: 구도 2개 중 하나만 건너뛰기 전 사진 → 그 구도만 채택, 장소는 SKIPPED.
        skip(1, 1, "2026-10-15T01:25:00Z").andExpect(status().isOk());
        submit(0, 1, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(4)).andExpect(status().isOk())
                .andExpect(jsonPath("$.captureStatus").value("ACCEPTED"));
        assertThat(stop(1)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForList("SELECT resolution FROM capture_slots WHERE patrol_stop_id = ? ORDER BY sequence_no", String.class,
                p.stops().get(1))).containsExactly("ACCEPTED", "SKIPPED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ? AND type = 'STOP_SKIPPED'", Integer.class, p.id()))
                .isEqualTo(3);
    }

    @Test
    void skipArrivingWhilePhotoIsProcessingIsSettledByThePhotoResult() throws Exception {
        // L5: 사진 처리 중 건너뛰기 → 보류, 채택되면 CAPTURED. 3a: 처리 중 건너뛰기 → 품질 실패면 건너뛰기 적용 → SKIPPED.
        for (String[] c : new String[][] {{"analyze_01_normal", "0", "CAPTURED"}, {"analyze_09_retake_dark", "3", "SKIPPED"}}) {
            ai.nextAnalyze(Reply.fixture(c[0]));
            int slot = Integer.parseInt(c[1]);
            CountDownLatch gate = new CountDownLatch(1);
            ai.holdAnalyze(gate);
            int before = ai.analyzeCalls();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> photo = pool.submit(() -> submit(0, slot, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:20:00Z",
                        MockAiServer.jpeg(slot + 1)).andReturn());
                waitForAnalyzeCalls(before + 1);
                skip(1, slot == 0 ? 0 : 2, "2026-10-15T01:25:00Z").andExpect(status().isOk()).andExpect(jsonPath("$.stopUpdated").value(false));
                assertThat(jdbc.queryForObject("SELECT skip_pending_event_id IS NOT NULL FROM patrol_stops WHERE id = ?", Boolean.class,
                        stopOf(slot))).isTrue();
                gate.countDown();
                assertThat(photo.get().getResponse().getStatus()).isEqualTo(200);
            } finally {
                gate.countDown();
                pool.shutdownNow();
            }
            ai.holdAnalyze(null);
            assertThat(jdbc.queryForObject("SELECT visit_status || ':' || (skip_pending_event_id IS NULL) FROM patrol_stops WHERE id = ?",
                    String.class, stopOf(slot))).isEqualTo(c[2] + ":true");
        }
    }

    @Test
    void invalidUploadsAreRejectedBeforeAnyRecordOrAiCall() throws Exception {
        submit(0, 0, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:20:00Z", "not an image".getBytes())
                .andExpect(status().isUnsupportedMediaType());
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        submit(0, 0, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:20:00Z", webp).andExpect(status().isUnsupportedMediaType());
        byte[] big = new byte[10_485_761];
        System.arraycopy(MockAiServer.jpeg(1), 0, big, 0, 3);
        submit(0, 0, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:20:00Z", big).andExpect(status().isContentTooLarge());
        String invalid = mvc.perform(withAuth(MockMvcRequestBuilders.multipart("/api/v1/patrols/{p}/stops/{s}/inspections", p.id(),
                p.stops().get(0)).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", MockAiServer.jpeg(1)))
                .param("clientSubmissionId", "x").param("deviceMasked", "false").param("deviceMaskReport", "{\"faces\":-1}")
                .param("latitude", "37.1"), p.users().get(0)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(invalid, "$.fieldErrors[*].field")).contains("clientSubmissionId", "captureSlotId", "attemptId",
                "capturedAt", "maskingVersion", "deviceMasked", "deviceMaskReport", "longitude");
        // 다른 순회의 구도·미래 시각·다른 활동일
        submit(0, 0, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T02:00:00Z", MockAiServer.jpeg(1))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_ACTIVITY_TIME"));
        submit(0, 0, UUID.randomUUID(), UUID.randomUUID(), "2026-10-14T01:20:00Z", MockAiServer.jpeg(1))
                .andExpect(status().isUnprocessableContent());
        PatrolFixture.Patrol other = f.patrol(2);
        mvc.perform(request(p.users().get(0), p.id(), p.stops().get(0), other.slots().get(0), UUID.randomUUID(), UUID.randomUUID(),
                "2026-10-15T01:20:00Z", MockAiServer.jpeg(1))).andExpect(status().isNotFound());
        mvc.perform(request(other.users().get(0), p.id(), p.stops().get(0), p.slots().get(0), UUID.randomUUID(), UUID.randomUUID(),
                "2026-10-15T01:20:00Z", MockAiServer.jpeg(1))).andExpect(status().isNotFound());
        assertThat(ai.analyzeCalls()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inspections i JOIN capture_slots s ON s.id = i.capture_slot_id "
                + "JOIN patrol_stops ps ON ps.id = s.patrol_stop_id WHERE ps.patrol_id = ?", Integer.class, p.id())).isZero();
    }
}
