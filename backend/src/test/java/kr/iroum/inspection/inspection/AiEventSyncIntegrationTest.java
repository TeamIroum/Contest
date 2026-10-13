package kr.iroum.inspection.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.iroum.inspection.analysis.event.AiEventSync;
import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;

/** AI /events 동기화(업무 API 15.3절, PHOTO-05): 재가림·보류·해제·재판독, 순서·중복·실패 시 커서 유지. mock AI 검증. */
class AiEventSyncIntegrationTest extends PhotoTestBase {

    @Autowired
    AiEventSync events;

    @BeforeEach
    void resetCursor() {
        jdbc.update("DELETE FROM ai_event_inbox WHERE provider = ?", AiEventSync.PROVIDER);
        jdbc.update("DELETE FROM ai_event_cursors WHERE provider = ?", AiEventSync.PROVIDER);
    }

    @Test
    void remaskCreatesNewMediaVersionAndOldCopyIsNoLongerCurrent() throws Exception {
        UUID inspection = accepted(0);
        String photo = photoId(inspection);
        UUID original = approved(inspection);
        long revision = revision();
        ai.remask(photo);
        AiEventSync.SyncReport report = events.sync();
        assertThat(report.applied()).isEqualTo(1);
        UUID fresh = approved(inspection);
        assertThat(fresh).isNotEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT superseded_by_media_id FROM media WHERE id = ?", UUID.class, original)).isEqualTo(fresh);
        assertThat(jdbc.queryForObject("SELECT provider_image_revision || ':' || privacy_status FROM media WHERE id = ?", String.class, fresh))
                .isEqualTo("2:APPROVED");
        assertThat(jdbc.queryForObject("SELECT media_id FROM inspections WHERE id = ?", UUID.class, inspection)).isEqualTo(original);
        assertThat(revision()).isGreaterThan(revision);
        // 같은 이벤트를 다시 받아도(커서 이하) 다시 반영하지 않는다.
        assertThat(events.sync().applied()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media WHERE provider_photo_id = ?", Integer.class, photo)).isEqualTo(2);
        assertThat(cursor()).isEqualTo(1);
    }

    @Test
    void hiddenBlocksImmediatelyHandsOverToReviewAndUnhiddenDoesNotPublish() throws Exception {
        UUID inspection = accepted(0);
        UUID media = approved(inspection);
        String photo = photoId(inspection);
        // 진행 중 재승인이 있었다면 새 보류로 무효가 된다.
        jdbc.update("""
                INSERT INTO privacy_reviews (program_id, media_id, decided_by, ok, reason, media_record_version, decided_media_version,
                    provider_sync_status, release_status, next_attempt_at, created_at) VALUES (?, ?, ?, true, '재확인', 0, 1, 'PENDING', 'PENDING', now(), now())""",
                f.program, media, f.manager);
        ai.hide(photo);
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("SELECT capture_status || ':' || (approved_media_id IS NULL) FROM inspections WHERE id = ?", String.class,
                inspection)).isEqualTo("REVIEW_REQUESTED:true");
        assertThat(jdbc.queryForObject("SELECT resolution || ':' || (accepted_inspection_id IS NULL) FROM capture_slots WHERE id = ?",
                String.class, p.slots().get(0))).isEqualTo("REVIEW_REQUESTED:true");
        assertThat(stop(0)).isEqualTo("REVIEW_REQUESTED");
        assertThat(jdbc.queryForObject("SELECT release_status || ':' || invalid_reason || ':' || provider_sync_status FROM privacy_reviews WHERE media_id = ?",
                String.class, media)).isEqualTo("INVALIDATED:NEW_PRIVACY_HOLD:FAILED");
        assertThat(tasks()).contains("INSPECTION:PRIVACY_HOLD:1");
        // 상태 조회는 검토 인계로 보인다.
        api.get(p.users().get(0), "/api/v1/inspections/" + inspection + "/capture-status").andExpect(jsonPath("$.captureStatus").value("REVIEW_REQUESTED"));
        // AI 보류 해제만으로는 공개하지 않는다(9.3절).
        ai.unhide(photo);
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("QUARANTINED");
        assertThat(cursor()).isEqualTo(2);
    }

    @Test
    void resultUpdatedOnlyAddsSnapshotAndEndsAiTracking() throws Exception {
        ai.nextAnalyze(Reply.fixture("analyze_15_needs_review_retry"));
        UUID submission = UUID.randomUUID();
        submit(0, 3, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isAccepted());
        UUID inspection = jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        ai.finishReanalysis(photoId(inspection), "analyze_02_observe");
        events.sync();
        assertThat(jdbc.queryForObject("""
                SELECT j.status || ':' || (SELECT count(*) FROM analysis_results r WHERE r.analysis_job_id = j.id) FROM analysis_jobs j
                WHERE j.inspection_id = ?""", String.class, inspection)).isEqualTo("SUCCEEDED:2");
        assertThat(jdbc.queryForObject("SELECT capture_status FROM inspections WHERE id = ?", String.class, inspection)).isEqualTo("REVIEW_REQUESTED");
        assertThat(jdbc.queryForObject("SELECT resolution FROM capture_slots WHERE id = ?", String.class, p.slots().get(3))).isEqualTo("REVIEW_REQUESTED");
    }

    @Test
    void failedEventKeepsCursorAndLaterEventsWaitInOrder() throws Exception {
        UUID inspection = accepted(0);
        String photo = photoId(inspection);
        ai.event("image_hidden", "insp_not_ours", 1); // 우리 사진이 아닌 이벤트는 반영할 것 없이 넘긴다.
        ai.remask(photo);
        ai.hide(photo);
        ai.nextImage(Reply.error(503, "UNAVAILABLE"));
        AiEventSync.SyncReport first = events.sync();
        assertThat(first.blocked()).isTrue();
        assertThat(cursor()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT seq || ':' || status FROM ai_event_inbox WHERE provider = ? ORDER BY seq", String.class,
                AiEventSync.PROVIDER)).containsExactly("1:APPLIED", "2:RETRY", "3:PENDING");
        // 뒤 이벤트(보류)는 앞 이벤트가 끝날 때까지 반영하지 않는다.
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, approved(inspection))).isEqualTo("APPROVED");
        clock.set(FixedClockConfiguration.NOW.plus(Duration.ofMinutes(5)));
        AiEventSync.SyncReport second = events.sync();
        assertThat(second.applied()).isEqualTo(2);
        assertThat(cursor()).isEqualTo(3);
        // 재시도 때 AI는 이미 그 사진을 보류(410)해 새 사본을 받을 수 없다. 재가림은 반영 없이 넘기고 뒤 이벤트가 지금 사본을 보류한다.
        // 새 AI 버전은 보류 해제(재승인) 때 버전 검증으로 확인한다(PHOTO-06).
        assertThat(jdbc.queryForObject("SELECT provider_image_revision || ':' || privacy_status FROM media WHERE provider_photo_id = ? AND superseded_by_media_id IS NULL",
                String.class, photo)).isEqualTo("1:QUARANTINED");
    }

    @Test
    void eventWaitingForRetryBlocksLaterEventsEvenWhenSyncRunsBeforeRetryTime() throws Exception {
        // 리뷰 재현: 앞 이벤트가 재시도 대기(next_run_at 미래)일 때 기한 전에 다시 동기화하면 뒤 이벤트를 먼저 반영하고 커서를 넘겨
        // 앞 이벤트가 영영 누락됐다.
        UUID inspection = accepted(0);
        String photo = photoId(inspection);
        UUID media = approved(inspection);
        ai.remask(photo);
        ai.nextImage(Reply.error(503, "UNAVAILABLE"));
        ai.event("image_hidden", photo, 1);
        assertThat(events.sync().blocked()).isTrue();
        for (int i = 0; i < 3; i++) {
            AiEventSync.SyncReport again = events.sync();
            assertThat(again.applied()).isZero();
            assertThat(again.blocked()).isTrue();
        }
        assertThat(cursor()).isZero();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForList("SELECT seq || ':' || status FROM ai_event_inbox WHERE provider = ? ORDER BY seq", String.class,
                AiEventSync.PROVIDER)).containsExactly("1:RETRY", "2:PENDING");
        // 재시도 시각이 지나면 앞에서부터 차례로 반영한다(재가림 → 새 사본 보류).
        clock.set(FixedClockConfiguration.NOW.plus(Duration.ofMinutes(5)));
        assertThat(events.sync().applied()).isEqualTo(2);
        assertThat(cursor()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT provider_image_revision || ':' || privacy_status FROM media WHERE provider_photo_id = ? AND superseded_by_media_id IS NULL""",
                String.class, photo)).isEqualTo("2:QUARANTINED");
    }

    @Test
    void holdEventNamingUnknownDecisionIsTreatedAsNewHold() throws Exception {
        // 메아리 판정은 reason이 이 사진의 우리 부족 판정을 가리킬 때만. 형식만 같은 표지는 새 보류다(PHOTO-15).
        UUID inspection = accepted(0);
        String photo = photoId(inspection);
        ai.setHidden(photo, true);
        ai.event("image_hidden", photo, 1, "itda-privacy-review:" + UUID.randomUUID());
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, approved(inspection))).isEqualTo("QUARANTINED");
    }

    @Test
    void sequenceBelowCursorIsTreatedAsSuspectedResetAndNothingIsApplied() throws Exception {
        UUID inspection = accepted(0);
        ai.hide(photoId(inspection));
        jdbc.update("INSERT INTO ai_event_cursors (provider, generation, last_applied_seq) VALUES (?, 'g1', 100)", AiEventSync.PROVIDER);
        assertThat(events.sync().blocked()).isTrue();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, approved(inspection))).isEqualTo("APPROVED");
    }

    private UUID accepted(int slot) throws Exception {
        UUID submission = UUID.randomUUID();
        submit(0, slot, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(slot + 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.captureStatus").value("ACCEPTED"));
        return jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
    }

    private String photoId(UUID inspection) {
        return jdbc.queryForObject("SELECT provider_photo_id FROM analysis_jobs WHERE inspection_id = ?", String.class, inspection);
    }

    private UUID approved(UUID inspection) {
        return jdbc.queryForObject("SELECT COALESCE(approved_media_id, media_id) FROM inspections WHERE id = ?", UUID.class, inspection);
    }

    private long revision() {
        return jdbc.queryForObject("SELECT revision FROM patrols WHERE id = ?", Long.class, p.id());
    }

    private long cursor() {
        return jdbc.queryForObject("SELECT last_applied_seq FROM ai_event_cursors WHERE provider = ?", Long.class, AiEventSync.PROVIDER);
    }
}
