package kr.iroum.inspection.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;

/** 접수 리스 만료·AI 재판독 추적 기한의 주기 복구(04단계 PHOTO-03). mock AI 검증. */
class IntakeRecoveryIntegrationTest extends PhotoTestBase {

    @Autowired
    IntakeRecovery recovery;

    @Test
    void expiredLeaseWithoutAiResultBecomesRetryRequiredAndLateOriginalResultIsDiscarded() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ai.holdAnalyze(gate);
        UUID submission = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> stuck = pool.submit(() -> submit(0, 0, submission, attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1))
                    .andReturn());
            waitForAnalyzeCalls(1);
            assertThat(recovery.recoverExpiredLeases()).isZero(); // 리스가 살아 있으면 건드리지 않는다.
            clock.set(FixedClockConfiguration.NOW.plus(Duration.ofMinutes(3)));
            assertThat(recovery.recoverExpiredLeases()).isEqualTo(1);
            assertThat(receipt(submission)).isEqualTo("RETRY_REQUIRED:TECHNICAL_FAILED");
            assertThat(jdbc.queryForObject("SELECT active_inspection_id IS NULL FROM capture_slots WHERE id = ?", Boolean.class,
                    p.slots().get(0))).isTrue();
            gate.countDown();
            // 늦게 끝난 원래 처리는 리스 토큰이 달라 결과를 버리고 사진 미확보(503)를 알린다.
            assertThat(stuck.get().getResponse().getStatus()).isEqualTo(503);
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
        ai.holdAnalyze(null);
        assertThat(receipt(submission)).isEqualTo("RETRY_REQUIRED:TECHNICAL_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media WHERE program_id = ?", Integer.class, f.program)).isZero();
        // 기기의 같은 ID 재전송으로 복구된다(AI는 같은 호출 ID의 저장 결과를 재현).
        submit(0, 0, submission, attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.captureStatus").value("ACCEPTED"));
    }

    @Test
    void expiredLeaseRecoversFromStoredAiResultWithoutDeviceResend() throws Exception {
        // 서버가 AI 응답을 받기 전에 멈춘 상태를 만든다: 시도 하나를 수신 중·리스 만료로 두고 AI에는 같은 호출 ID 결과가 저장돼 있다.
        ai.nextAnalyze(Reply.error(503, "UNAVAILABLE"));
        UUID submission = UUID.randomUUID();
        submit(0, 1, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isServiceUnavailable());
        UUID inspection = jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        String photoId = jdbc.queryForObject("SELECT provider_photo_id FROM analysis_jobs WHERE inspection_id = ?", String.class, inspection);
        jdbc.update("""
                UPDATE inspections SET receipt_status = 'RECEIVING', capture_status = 'PROCESSING', ingestion_lease_token = gen_random_uuid(),
                    ingestion_lease_until = '2026-10-15T01:20:00Z' WHERE id = ?""", inspection);
        jdbc.update("UPDATE capture_slots SET active_inspection_id = ? WHERE id = ?", inspection, p.slots().get(1));
        jdbc.update("UPDATE analysis_jobs SET status = 'PROCESSING' WHERE inspection_id = ?", inspection);
        ai.finishReanalysis(photoId, "analyze_02_observe");

        assertThat(recovery.recoverExpiredLeases()).isEqualTo(1);
        assertThat(receipt(submission)).isEqualTo("RECEIVED:ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT accepted_inspection_id FROM capture_slots WHERE id = ?", UUID.class, p.slots().get(1)))
                .isEqualTo(inspection);
        assertThat(tasks()).containsExactly("INSPECTION:ISSUE_SUSPECTED:1");
        // 기기의 늦은 재전송은 같은 결과를 duplicate=true로 재현한다.
        api.get(p.users().get(0), "/api/v1/inspections/" + inspection + "/capture-status").andExpect(jsonPath("$.captureStatus").value("ACCEPTED"));
    }

    @Test
    void aiReanalysisTrackingEndsAtDeadlineAndOnlyAddsSnapshots() throws Exception {
        ai.nextAnalyze(Reply.fixture("analyze_15_needs_review_retry"), Reply.fixture("analyze_15_needs_review_retry"));
        UUID stillPending = UUID.randomUUID();
        UUID finished = UUID.randomUUID();
        submit(0, 1, stillPending, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isAccepted());
        submit(0, 3, finished, UUID.randomUUID(), "2026-10-15T01:21:00Z", MockAiServer.jpeg(2)).andExpect(status().isAccepted());
        ai.finishReanalysis(photoId(finished), "analyze_02_observe");
        assertThat(recovery.closeExpiredAiTracking()).isZero(); // 기한 전에는 추적을 계속한다.
        clock.set(FixedClockConfiguration.NOW.plus(Duration.ofMinutes(31)));
        assertThat(recovery.closeExpiredAiTracking()).isEqualTo(2);
        assertThat(job(stillPending)).isEqualTo("FAILED:REANALYSIS_DEADLINE:1");
        assertThat(job(finished)).isEqualTo("SUCCEEDED:-:2");
        // 재판독 결과는 스냅샷만 추가한다. 구도·사진 상태·검토 작업은 그대로다.
        assertThat(receipt(finished)).isEqualTo("RECEIVED:REVIEW_REQUESTED");
        assertThat(jdbc.queryForObject("SELECT resolution FROM capture_slots WHERE id = ?", String.class, p.slots().get(3)))
                .isEqualTo("REVIEW_REQUESTED");
        assertThat(recovery.closeExpiredAiTracking()).isZero();
    }

    private String receipt(UUID submission) {
        return jdbc.queryForObject("SELECT receipt_status || ':' || capture_status FROM inspections WHERE client_submission_id = ?",
                String.class, submission);
    }

    private String photoId(UUID submission) {
        return jdbc.queryForObject("""
                SELECT j.provider_photo_id FROM analysis_jobs j JOIN inspections i ON i.id = j.inspection_id WHERE i.client_submission_id = ?""",
                String.class, submission);
    }

    private String job(UUID submission) {
        return jdbc.queryForObject("""
                SELECT j.status || ':' || COALESCE(j.error_code, '-') || ':' || (SELECT count(*) FROM analysis_results r WHERE r.analysis_job_id = j.id)
                FROM analysis_jobs j JOIN inspections i ON i.id = j.inspection_id WHERE i.client_submission_id = ?""", String.class, submission);
    }
}
