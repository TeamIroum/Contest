package kr.iroum.inspection.inspection;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;

/** 서버 보관본 기술 재처리(업무 API 6.4절, PHOTO-04). mock AI 검증. */
class ProcessingRetryIntegrationTest extends PhotoTestBase {

    @Test
    void storedPhotoIsReanalysedWithNewJobGenerationAndIdempotentReplay() throws Exception {
        UUID inspection = technicalFailure(0);
        UUID originalMedia = jdbc.queryForObject("SELECT media_id FROM inspections WHERE id = ?", UUID.class, inspection);
        String firstPhotoId = photoIds(inspection);
        UUID key = UUID.randomUUID();
        String body = retry(0, inspection, key).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.captureStatus").value("PROCESSING")).andExpect(jsonPath("$.receiptStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.nextAction").value("WAIT")).andExpect(jsonPath("$.message").value("사진을 다시 확인하고 있어요."))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ProcessingRetry", body);
        String done = awaitFinal(inspection);
        assertThat(JsonPath.<String>read(done, "$.captureStatus")).isEqualTo("ACCEPTED");
        assertThat(JsonPath.<Integer>read(done, "$.attemptNo")).isEqualTo(1);
        // 새 작업 세대·새 AI 호출 ID, 접수 사실(media_id)은 그대로, 승인 포인터는 새 검사본으로.
        assertThat(jdbc.queryForList("SELECT input_version || ':' || status FROM analysis_jobs WHERE inspection_id = ? ORDER BY input_version",
                String.class, inspection)).containsExactly("1:SUCCEEDED", "2:SUCCEEDED");
        assertThat(photoIds(inspection)).startsWith(firstPhotoId + ",insp_").isNotEqualTo(firstPhotoId + "," + firstPhotoId);
        assertThat(jdbc.queryForObject("SELECT media_id FROM inspections WHERE id = ?", UUID.class, inspection)).isEqualTo(originalMedia);
        UUID approved = jdbc.queryForObject("SELECT approved_media_id FROM inspections WHERE id = ?", UUID.class, inspection);
        assertThat(approved).isNotEqualTo(originalMedia);
        assertThat(jdbc.queryForObject("SELECT superseded_by_media_id FROM media WHERE id = ?", UUID.class, originalMedia)).isEqualTo(approved);
        assertThat(jdbc.queryForObject("SELECT source_media_id FROM media WHERE id = ?", UUID.class, approved)).isEqualTo(originalMedia);
        assertThat(jdbc.queryForObject("SELECT accepted_inspection_id FROM capture_slots WHERE id = ?", UUID.class, p.slots().get(0)))
                .isEqualTo(inspection);
        // 같은 키는 최초 응답 재현(새 작업 없음), 같은 키로 다른 사진은 409.
        int calls = ai.analyzeCalls();
        retry(0, inspection, key).andExpect(status().isAccepted()).andExpect(jsonPath("$.captureStatus").value("PROCESSING"));
        assertThat(ai.analyzeCalls()).isEqualTo(calls);
        UUID other = technicalFailure(3);
        retry(0, other, key).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 이미 끝난 결과에 새 키로 요청해도 다시 판독하지 않는다.
        calls = ai.analyzeCalls();
        retry(0, inspection, UUID.randomUUID()).andExpect(status().isAccepted()).andExpect(jsonPath("$.captureStatus").value("ACCEPTED"));
        assertThat(ai.analyzeCalls()).isEqualTo(calls);
    }

    @Test
    void failedReprocessKeepsStoredPhotoAndUnsecuredPhotoNeedsResubmission() throws Exception {
        UUID inspection = technicalFailure(0);
        ai.nextAnalyze(Reply.error(503, "UNAVAILABLE"));
        retry(1, inspection, UUID.randomUUID()).andExpect(status().isAccepted());
        String done = awaitFinal(inspection);
        assertThat(JsonPath.<String>read(done, "$.receiptStatus")).isEqualTo("RECEIVED");
        assertThat(JsonPath.<String>read(done, "$.captureStatus")).isEqualTo("TECHNICAL_FAILED");
        assertThat(JsonPath.<String>read(done, "$.nextAction")).isEqualTo("RETRY");
        assertThat(jdbc.queryForObject("SELECT active_inspection_id IS NULL AND quality_failure_count = 0 FROM capture_slots WHERE id = ?",
                Boolean.class, p.slots().get(0))).isTrue();

        // 사진을 확보하지 못한 시도는 재처리하지 않고 같은 ID·파일 재전송을 안내한다.
        ai.nextAnalyze(Reply.error(503, "UNAVAILABLE"));
        UUID submission = UUID.randomUUID();
        submit(0, 3, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(5)).andExpect(status().isServiceUnavailable());
        UUID unsecured = jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        String conflict = retry(0, unsecured, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHOTO_RESUBMISSION_REQUIRED"))
                .andExpect(jsonPath("$.details.receiptStatus").value("RETRY_REQUIRED")).andReturn().getResponse().getContentAsString();
        assertMatches("ErrorPhotoResubmissionRequired", conflict);

        // 검토로 인계된 구도는 다시 열지 않는다. 다른 순회 사용자는 404.
        jdbc.update("UPDATE capture_slots SET resolution = 'REVIEW_REQUESTED' WHERE id = ?", p.slots().get(0));
        retry(0, inspection, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_RESOLVED"));
        kr.iroum.inspection.support.PatrolFixture.Patrol other = f.patrol(2);
        api.post(other.users().get(0), "/api/v1/inspections/" + inspection + "/processing-retries", "", UUID.randomUUID())
                .andExpect(status().isNotFound());
    }

    /** 채택된 사진을 '보관본은 있으나 판독 처리 실패' 상태로 되돌린다(재처리 대상 상태 준비). */
    private UUID technicalFailure(int slot) throws Exception {
        UUID submission = UUID.randomUUID();
        submit(0, slot, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(slot + 1)).andExpect(status().isOk());
        UUID inspection = jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        jdbc.update("UPDATE capture_slots SET accepted_inspection_id = NULL, resolution = 'OPEN' WHERE id = ?", p.slots().get(slot));
        jdbc.update("UPDATE inspections SET capture_status = 'TECHNICAL_FAILED' WHERE id = ?", inspection);
        jdbc.update("UPDATE patrol_stops SET visit_status = 'PENDING' WHERE id = ?", stopOf(slot));
        return inspection;
    }

    private ResultActions retry(int member, UUID inspection, UUID key) throws Exception {
        return api.post(p.users().get(member), "/api/v1/inspections/" + inspection + "/processing-retries", "", key);
    }

    private String photoIds(UUID inspection) {
        return jdbc.queryForObject("SELECT string_agg(provider_photo_id, ',' ORDER BY input_version) FROM analysis_jobs WHERE inspection_id = ?",
                String.class, inspection);
    }

    private String awaitFinal(UUID inspection) throws Exception {
        for (int i = 0; i < 200; i++) {
            String body = api.get(p.users().get(0), "/api/v1/inspections/" + inspection + "/capture-status").andReturn().getResponse()
                    .getContentAsString();
            if (!"PROCESSING".equals(JsonPath.read(body, "$.captureStatus"))) {
                assertMatches("CaptureResult", body);
                return body;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("재처리가 끝나지 않음");
    }
}
