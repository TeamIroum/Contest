package kr.iroum.inspection.inspection;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.analysis.event.AiEventSync;
import kr.iroum.inspection.media.PrivacyReleaseSender;
import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;

/** 개인정보 보류·재승인·재요청·무효화(업무 API 9.3절, D-28, PHOTO-06). mock AI 검증. */
class PrivacyReleaseIntegrationTest extends PhotoTestBase {

    @Autowired
    PrivacyReleaseSender sender;

    @Autowired
    AiEventSync events;

    UUID facilityManager;
    UUID inspection;
    UUID media;
    String photo;

    @BeforeEach
    void photo() throws Exception {
        jdbc.update("DELETE FROM ai_event_inbox WHERE provider = ?", AiEventSync.PROVIDER);
        jdbc.update("DELETE FROM ai_event_cursors WHERE provider = ?", AiEventSync.PROVIDER);
        facilityManager = f.user("정○○");
        f.role(facilityManager, "FACILITY_MANAGER", f.department);
        UUID submission = UUID.randomUUID();
        submit(0, 0, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk());
        inspection = jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        media = jdbc.queryForObject("SELECT approved_media_id FROM inspections WHERE id = ?", UUID.class, inspection);
        photo = jdbc.queryForObject("SELECT provider_photo_id FROM media WHERE id = ?", String.class, media);
    }

    @Test
    void holdBlocksImmediatelyAndReleasePublishesOnlyPhotoViewAfterAiSuccess() throws Exception {
        UUID key = UUID.randomUUID();
        String held = review(false, 0, key).andExpect(status().isOk()).andExpect(jsonPath("$.privacyStatus").value("QUARANTINED"))
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.release").isEmpty())
                .andExpect(jsonPath("$.affectedInspectionIds[0]").value(inspection.toString()))
                .andReturn().getResponse().getContentAsString();
        assertMatches("PrivacyResult", held);
        // 차단은 전송 결과를 기다리지 않고, AI에는 ok 불리언만 간다(실명·메모 없음).
        assertThat(ai.auditRequests()).hasSize(1);
        // 본문은 ok 불리언과 개인정보 없는 연결 표지(note=결정 ID)뿐이다. 실명·사유 원문은 보내지 않는다.
        com.fasterxml.jackson.databind.JsonNode sent = (com.fasterxml.jackson.databind.JsonNode) ai.auditRequests().get(0).get("body");
        assertThat(sent.get("ok").asBoolean()).isFalse();
        assertThat(sent.get("note").asText()).matches("itda-privacy-review:[0-9a-f-]{36}");
        assertThat(sent.has("reviewer")).isFalse();
        assertThat(sent.toString()).doesNotContain("가림 재확인");
        assertThat(sync()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT capture_status FROM inspections WHERE id = ?", String.class, inspection)).isEqualTo("REVIEW_REQUESTED");
        // 같은 키 재전송은 최초 결과, 같은 키·다른 내용은 409.
        review(false, 0, key).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        review(true, 0, key).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 오래된 version은 409.
        review(true, 0, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        String pending = review(true, 1, UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.privacyStatus").value("QUARANTINED")).andExpect(jsonPath("$.release.status").value("PENDING"))
                .andExpect(jsonPath("$.release.decidedMediaVersion").value(1)).andReturn().getResponse().getContentAsString();
        assertMatches("PrivacyResult", pending);
        // 커밋 뒤 전송 → AI 해제 성공·버전 확인 → 공개. 공개는 사진 조회만 복구한다(채택·구도·장소는 그대로).
        assertThat(release()).isEqualTo("COMPLETED:-:-");
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        assertThat(ai.hidden(photo)).isFalse();
        assertThat(jdbc.queryForObject("SELECT capture_status || ':' || (approved_media_id IS NULL) FROM inspections WHERE id = ?", String.class,
                inspection)).isEqualTo("REVIEW_REQUESTED:true");
        assertThat(jdbc.queryForObject("SELECT resolution FROM capture_slots WHERE id = ?", String.class, p.slots().get(0))).isEqualTo("REVIEW_REQUESTED");
        // 공개 뒤 새 보류는 새 보류 기간: release는 null로 돌아간다.
        String again = review(false, 2, UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.release").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertMatches("PrivacyResult", again);
    }

    @Test
    void transientFailuresRetryUpToLimitThenManagerRetryStartsNewCycle() throws Exception {
        review(false, 0, UUID.randomUUID()).andExpect(status().isOk());
        ai.nextAudit(Reply.error(503, "UNAVAILABLE"), Reply.drop(), Reply.error(429, "RATE"), Reply.error(500, "X"), Reply.error(503, "UNAVAILABLE"));
        review(true, 1, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(release()).isEqualTo("PENDING:-:-");
        // 재승인 진행 중에는 새 재승인 결정 409.
        String conflict = review(true, 1, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRIVACY_RELEASE_CONFLICT")).andExpect(jsonPath("$.details.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorPrivacyReleaseConflict", conflict);
        // 30초·1분·2분·4분 간격으로 다시 보내고 다섯 번째 실패에서 소진.
        for (long wait : new long[] {30, 60, 120, 240}) {
            assertThat(sender.drain()).isZero();
            clock.set(clock.instant().plus(Duration.ofSeconds(wait)));
            assertThat(sender.drain()).isEqualTo(1);
        }
        assertThat(release()).isEqualTo("FAILED:RETRY_EXHAUSTED:-");
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("QUARANTINED");
        UUID releaseId = jdbc.queryForObject("SELECT id FROM privacy_reviews WHERE media_id = ? AND release_status IS NOT NULL", UUID.class, media);
        review(true, 1, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.details.status").value("FAILED"));
        retry(UUID.randomUUID(), 1, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRIVACY_RELEASE_CONFLICT"));
        retry(releaseId, 0, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        clock.set(clock.instant().plus(Duration.ofSeconds(4))); // 마지막 일시 장애 뒤 대기 구간(PHOTO-13)
        String restarted = retry(releaseId, 1, UUID.randomUUID()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.release.status").value("PENDING")).andExpect(jsonPath("$.release.retryable").value(false))
                .andReturn().getResponse().getContentAsString();
        assertMatches("PrivacyResult", restarted);
        assertThat(release()).isEqualTo("COMPLETED:-:-");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM privacy_reviews WHERE id = ?", Integer.class, releaseId)).isEqualTo(6);
    }

    @Test
    void providerRejectionsAreNotRepeated() throws Exception {
        review(false, 0, UUID.randomUUID()).andExpect(status().isOk());
        ai.nextAudit(Reply.error(400, "INVALID_AUDIT"));
        review(true, 1, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(release()).isEqualTo("FAILED:PROVIDER_REJECTED:-");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM privacy_reviews WHERE media_id = ? AND release_status IS NOT NULL",
                String.class, media)).isEqualTo("INVALID_AUDIT");
        UUID releaseId = jdbc.queryForObject("SELECT id FROM privacy_reviews WHERE media_id = ? AND release_status IS NOT NULL", UUID.class, media);
        // AI가 성공을 돌려줬지만 보류가 풀리지 않았으면(hidden=true) 역시 거절로 본다.
        ai.nextAudit(Reply.fixture("hidden-stays"));
        retry(releaseId, 1, UUID.randomUUID()).andExpect(status().isAccepted());
        assertThat(release()).isEqualTo("FAILED:PROVIDER_REJECTED:-");
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("QUARANTINED");
    }

    @Test
    void newHoldOrRemaskInvalidatesPendingReleaseAndNothingIsPublished() throws Exception {
        review(false, 0, UUID.randomUUID()).andExpect(status().isOk());
        ai.nextAudit(Reply.error(503, "UNAVAILABLE"));
        review(true, 1, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(release()).isEqualTo("PENDING:-:-");
        // AI가 다시 보류(image_hidden): 진행 중 재승인 무효(NEW_PRIVACY_HOLD), 자동 재시도 중단.
        ai.event("image_hidden", photo, 1);
        events.sync();
        assertThat(release()).isEqualTo("INVALIDATED:-:NEW_PRIVACY_HOLD");
        clock.set(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(sender.drain()).isZero();
        // 무효가 된 처리는 재요청할 수 없고 새 결정을 내린다. 결정 뒤 AI가 재가림했으면 해제 성공 뒤 버전 확인에서 공개하지 않는다.
        UUID invalid = jdbc.queryForObject("SELECT id FROM privacy_reviews WHERE media_id = ? AND release_status = 'INVALIDATED'", UUID.class, media);
        long version = jdbc.queryForObject("SELECT version FROM media WHERE id = ?", Long.class, media);
        retry(invalid, version, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.details.status").value("INVALIDATED"));
        ai.remask(photo); // AI 쪽 사진 버전 2(알림은 아직 반영 안 함)
        review(true, version, UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.release.status").value("PENDING"));
        assertThat(jdbc.queryForObject("""
                SELECT release_status || ':' || invalid_reason FROM privacy_reviews WHERE media_id = ? AND release_status IS NOT NULL
                ORDER BY created_at DESC, id DESC LIMIT 1""", String.class, media)).isEqualTo("INVALIDATED:REMASKED");
        String current = jdbc.queryForObject("""
                SELECT provider_image_revision || ':' || privacy_status FROM media WHERE provider_photo_id = ? AND superseded_by_media_id IS NULL""",
                String.class, photo);
        assertThat(current).isEqualTo("2:QUARANTINED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media WHERE provider_photo_id = ? AND privacy_status = 'APPROVED'", Integer.class, photo))
                .isZero();
    }

    @Test
    void remaskedCopyCreatedWhileHeldKeepsInspectionAndJurisdictionLink() throws Exception {
        // 리뷰 재현: 보류 중(승인 포인터 해제) 재가림 사본이 생기면 점검·시설 연결을 찾지 못해 관할 담당자도 404였다.
        review(false, 0, UUID.randomUUID()).andExpect(status().isOk());
        ai.remask(photo);
        review(true, 1, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(release()).isEqualTo("INVALIDATED:-:REMASKED");
        UUID fresh = jdbc.queryForObject("SELECT id FROM media WHERE provider_photo_id = ? AND superseded_by_media_id IS NULL", UUID.class, photo);
        assertThat(fresh).isNotEqualTo(media);
        long version = jdbc.queryForObject("SELECT version FROM media WHERE id = ?", Long.class, fresh);
        // 관할 담당자는 새 사본을 확인하고(조회) 재승인할 수 있다. 응답의 영향 사진에 원래 점검이 보인다.
        api.get(facilityManager, "/api/v1/media/" + fresh + "/content").andExpect(status().isNotFound()); // 보류 중이라 공개 안 됨
        String result = api.post(facilityManager, "/api/v1/admin/media/" + fresh + "/privacy-reviews", body(true, version), UUID.randomUUID())
                .andExpect(status().isOk()).andExpect(jsonPath("$.affectedInspectionIds[0]").value(inspection.toString()))
                .andReturn().getResponse().getContentAsString();
        assertMatches("PrivacyResult", result);
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, fresh)).isEqualTo("APPROVED");
        api.get(facilityManager, "/api/v1/media/" + fresh + "/content").andExpect(status().isOk());
        // 다른 부서 담당자는 여전히 404, 구도 채택·사진 상태는 자동 복원하지 않는다.
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 하수과");
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", otherDepartment);
        api.get(other, "/api/v1/media/" + fresh + "/content").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT capture_status || ':' || (approved_media_id IS NULL) FROM inspections WHERE id = ?", String.class,
                inspection)).isEqualTo("REVIEW_REQUESTED:true");
        assertThat(jdbc.queryForObject("SELECT resolution || ':' || (accepted_inspection_id IS NULL) FROM capture_slots WHERE id = ?",
                String.class, p.slots().get(0))).isEqualTo("REVIEW_REQUESTED:true");
        // 새 사본의 새 보류도 원래 점검·구도를 찾아 순회 revision을 올린다.
        long revision = jdbc.queryForObject("SELECT revision FROM patrols WHERE id = ?", Long.class, p.id());
        ai.event("image_hidden", photo, 2);
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, fresh)).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("SELECT revision FROM patrols WHERE id = ?", Long.class, p.id())).isGreaterThan(revision);
    }

    @Test
    void staleHoldRetryIsNotSentAfterNewerRelease() throws Exception {
        // 리뷰 재현: 일시 장애로 재시도 대기 중이던 과거 ok:false가 최신 ok:true 공개 뒤에 전송돼 AI는 보류, 업무는 공개로 어긋났다.
        ai.nextAudit(Reply.error(503, "UNAVAILABLE"));
        review(false, 0, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(sync()).isEqualTo("PENDING");
        review(true, 1, UUID.randomUUID()).andExpect(status().isOk());
        // 끊긴 ok:false가 AI에서 늦게 반영될 수 있는 동안(AI 호출 제한 시간, 시험 3초)은 보내지 않는다(PHOTO-13).
        assertThat(release()).isEqualTo("PENDING:-:-");
        clock.set(clock.instant().plus(Duration.ofSeconds(4)));
        sender.drain();
        assertThat(release()).isEqualTo("COMPLETED:-:-");
        clock.set(clock.instant().plus(Duration.ofMinutes(10)));
        sender.drain();
        assertThat(ai.hidden(photo)).as("AI 보류 상태").isFalse();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        assertThat(sentOks()).containsExactly(false, true);
        assertThat(jdbc.queryForObject("SELECT provider_sync_status || ':' || last_error_code FROM privacy_reviews WHERE media_id = ? AND NOT ok",
                String.class, media)).isEqualTo("FAILED:SUPERSEDED");
    }

    @Test
    void decisionsForOnePhotoAreSentOneAtATimeInDecisionOrder() throws Exception {
        // 리뷰 재현: ok:false 전송이 AI에서 지연되는 동안 ok:true가 먼저 도착·공개되고, 뒤늦게 도착한 ok:false가 AI를 다시 보류시켰다.
        CountDownLatch gate = new CountDownLatch(1);
        ai.holdNextAudit(gate);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> hold = pool.submit(() -> {
                review(false, 0, UUID.randomUUID()).andExpect(status().isOk());
                return null;
            });
            for (int i = 0; i < 200 && ai.auditRequests().isEmpty(); i++) {
                Thread.sleep(25);
            }
            assertThat(ai.auditRequests()).hasSize(1);
            review(true, 1, UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.release.status").value("PENDING"));
            // 앞 결정이 AI에서 처리 중이면 같은 사진의 다음 결정은 보내지 않는다.
            assertThat(ai.auditRequests()).hasSize(1);
            gate.countDown();
            hold.get();
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
        sender.drain();
        assertThat(sentOks()).containsExactly(false, true);
        assertThat(ai.hidden(photo)).isFalse();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        assertThat(release()).isEqualTo("COMPLETED:-:-");
    }

    @Test
    void decisionTimedOutAtClientIsNotOvertakenWhileAiMayStillApplyIt() throws Exception {
        // 경계 검토: ok:false가 클라이언트 제한 시간(시험 3초)으로 끊긴 뒤에도 AI는 그 요청을 늦게 처리할 수 있다.
        // 끊긴 직후 최신 ok:true를 바로 보내면 늦게 처리된 ok:false가 AI를 다시 보류시켜 업무 공개와 어긋난다.
        CountDownLatch gate = new CountDownLatch(1);
        ai.holdNextAudit(gate);
        try {
            review(false, 0, UUID.randomUUID()).andExpect(status().isOk()); // 전송은 3초 뒤 시간 초과(TRANSIENT)
            assertThat(jdbc.queryForObject("SELECT last_error_code FROM privacy_reviews WHERE media_id = ? AND NOT ok", String.class, media))
                    .isNotNull();
            review(true, 1, UUID.randomUUID()).andExpect(status().isOk());
            // 끊긴 요청이 AI에서 끝날 수 있는 동안은 같은 사진의 새 결정을 보내지 않는다.
            assertThat(ai.auditRequests()).hasSize(1);
        } finally {
            gate.countDown(); // AI가 늦게 ok:false를 반영
        }
        for (int i = 0; i < 200 && !ai.hidden(photo); i++) {
            Thread.sleep(25);
        }
        assertThat(ai.hidden(photo)).isTrue();
        clock.set(clock.instant().plus(Duration.ofMinutes(2)));
        sender.drain();
        assertThat(sentOks()).containsExactly(false, true);
        assertThat(ai.hidden(photo)).isFalse();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
    }

    @Test
    void echoedEventsOfOwnDecisionsDoNotOverrideLatestDecision() throws Exception {
        // 실제 AI 연결 재현(2026-10-13): AI는 가림 점검 기록으로 보류 상태가 바뀌면 image_hidden·image_unhidden 알림을 남긴다(reason=요청 note). 보류 → 재승인 공개 뒤
        // 이 알림을 동기화하면 지난 보류 알림이 공개된 사진을 다시 보류해 AI(해제)와 업무(보류)가 어긋났다.
        review(false, 0, UUID.randomUUID()).andExpect(status().isOk());
        review(true, 1, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(release()).isEqualTo("COMPLETED:-:-");
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        assertThat(ai.hidden(photo)).isFalse();
        assertThat(release()).isEqualTo("COMPLETED:-:-");
        // 재승인 전송 전(AI는 아직 보류)에 우리 보류 알림이 와도 진행 중 재승인을 무효로 만들지 않는다.
        long version = jdbc.queryForObject("SELECT version FROM media WHERE id = ?", Long.class, media);
        clock.set(clock.instant().plusSeconds(1)); // 고정 시계에서 결정 순서가 같은 시각이 되지 않게
        review(false, version, UUID.randomUUID()).andExpect(status().isOk());
        ai.nextAudit(Reply.error(503, "UNAVAILABLE"));
        clock.set(clock.instant().plusSeconds(1));
        review(true, version + 1, UUID.randomUUID()).andExpect(status().isOk());
        events.sync();
        assertThat(release()).isEqualTo("PENDING:-:-");
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        sender.drain();
        events.sync();
        assertThat(release()).isEqualTo("COMPLETED:-:-");
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        assertThat(ai.hidden(photo)).isFalse();
    }

    @Test
    void repeatedHoldDoesNotLeaveStaleEchoThatSwallowsLaterNewHold() throws Exception {
        // 대조 재현(c96bd57 ai/activity.py): AI는 공개→보류일 때만 image_hidden을 남긴다. 같은 사진에 ok:false를 두 번 성공시켜도 알림은 한 건이다.
        // 알림이 결정 수만큼 온다고 가정하면 남은 '메아리 후보'가 나중의 새 보류(다른 점검 경로)를 메아리로 삼켜 조회가 열린 채 남는다.
        review(false, 0, UUID.randomUUID()).andExpect(status().isOk());
        clock.set(clock.instant().plusSeconds(1));
        review(false, 1, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT provider_sync_status FROM privacy_reviews WHERE media_id = ? AND NOT ok ORDER BY created_at",
                String.class, media)).containsExactly("SENT", "SENT");
        assertThat(ai.auditRequests()).hasSize(2);
        clock.set(clock.instant().plusSeconds(1));
        review(true, 2, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(release()).isEqualTo("COMPLETED:-:-");
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        // 업무 백엔드가 아닌 경로의 새 가림 점검 보류
        ai.hide(photo);
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).as("새 보류 반영")
                .isEqualTo("QUARANTINED");
        api.get(facilityManager, "/api/v1/media/" + media + "/content").andExpect(status().isNotFound());
    }

    @Test
    void reviewLateOldHoldAfterSettleMustBlockPublishedMedia() throws Exception {
        // Codex 재현(2026-10-13): 첫 ok:false가 AI에서 지연돼 클라이언트 시간 초과 → 최신 ok:true를 대기 구간 뒤 보내 공개 →
        // 그 뒤에 첫 ok:false가 AI에서 실제로 실행돼 다시 보류. 알림 reason이 우리 결정이라는 이유만으로 무시하면 AI는 보류, 업무는 공개로 남는다.
        CountDownLatch gate = new CountDownLatch(1);
        ai.holdNextAudit(gate);
        try {
            review(false, 0, UUID.randomUUID()).andExpect(status().isOk()); // AI 처리 지연 → 3초 시간 초과(TRANSIENT)
            clock.set(clock.instant().plusSeconds(1));
            review(true, 1, UUID.randomUUID()).andExpect(status().isOk());
            clock.set(clock.instant().plusSeconds(4)); // 대기 구간이 지남
            sender.drain();
            assertThat(release()).isEqualTo("COMPLETED:-:-");
            assertThat(ai.hidden(photo)).isFalse();
        } finally {
            gate.countDown(); // 지연됐던 첫 ok:false가 이제 AI에서 실행됨
        }
        for (int i = 0; i < 200 && !ai.hidden(photo); i++) {
            Thread.sleep(25);
        }
        assertThat(ai.hidden(photo)).isTrue();
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("QUARANTINED");
        api.get(facilityManager, "/api/v1/media/" + media + "/content").andExpect(status().isNotFound());
        // 업무는 보류 검토로 넘기고 담당자가 다시 결정한다(공개 결정을 자동 재전송하지 않음).
        assertThat(tasks()).contains("INSPECTION:PRIVACY_HOLD:1");
        long version = jdbc.queryForObject("SELECT version FROM media WHERE id = ?", Long.class, media);
        clock.set(clock.instant().plusSeconds(1));
        review(true, version, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        assertThat(ai.hidden(photo)).isFalse();
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
    }

    @Test
    void ownHoldEventWaitsWhileSendForSamePhotoIsInFlight() throws Exception {
        // 동시 실행: 업무 사본이 공개 상태일 때 우리 과거 부족 판정의 알림을 처리하려는데 같은 사진의 전송이 진행 중이면, 그 결과가 확정될 때까지
        // 판단을 미룬다(커서 유지). 확정 뒤 AI가 보류가 아니면 지난 알림으로 넘긴다.
        review(false, 0, UUID.randomUUID()).andExpect(status().isOk());
        clock.set(clock.instant().plusSeconds(1));
        review(true, 1, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(release()).isEqualTo("COMPLETED:-:-");
        UUID releaseId = jdbc.queryForObject("SELECT id FROM privacy_reviews WHERE media_id = ? AND ok", UUID.class, media);
        jdbc.update("UPDATE privacy_reviews SET sending_until = ? WHERE id = ?",
                java.sql.Timestamp.from(clock.instant().plusSeconds(60)), releaseId); // 같은 사진 전송 점유 중
        AiEventSync.SyncReport report = events.sync();
        assertThat(report.blocked()).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_applied_seq FROM ai_event_cursors WHERE provider = ?", Long.class, AiEventSync.PROVIDER))
                .isZero();
        jdbc.update("UPDATE privacy_reviews SET sending_until = NULL WHERE id = ?", releaseId);
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        assertThat(events.sync().blocked()).isFalse();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        assertThat(ai.hidden(photo)).isFalse();
    }

    /** 보류 → 재승인 공개 → 지난 알림 동기화까지 마치고 첫 부족 판정 ID를 돌려준다. */
    private UUID publishedAfterHoldAndRelease() throws Exception {
        review(false, 0, UUID.randomUUID()).andExpect(status().isOk());
        UUID hold = jdbc.queryForObject("SELECT id FROM privacy_reviews WHERE media_id = ? AND NOT ok", UUID.class, media);
        clock.set(clock.instant().plusSeconds(1));
        review(true, 1, UUID.randomUUID()).andExpect(status().isOk());
        assertThat(release()).isEqualTo("COMPLETED:-:-");
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        return hold;
    }

    private long eventCursor() {
        return jdbc.queryForObject("SELECT last_applied_seq FROM ai_event_cursors WHERE provider = ?", Long.class, AiEventSync.PROVIDER);
    }

    @Test
    void aiStateCheckFailureKeepsLateHoldEventUnprocessed() throws Exception {
        // 경계 A: 우리 과거 부족 판정 알림 처리 중 AI 현재 상태 조회가 연결 실패·시간 초과·5xx면 처리 완료하거나 커서를 넘기지 않는다.
        UUID hold = publishedAfterHoldAndRelease();
        long cursor = eventCursor();
        ai.setHidden(photo, true); // 과거 부족 판정이 늦게 실행돼 AI가 다시 보류됨
        ai.event("image_hidden", photo, 1, "itda-privacy-review:" + hold);
        // 연결 끊김은 Java HTTP 클라이언트가 GET을 한 번 자동 재시도하므로 5xx로 반복한다(시간 초과·끊김 분류는 AiClientContractTest).
        ai.nextImage(Reply.error(503, "UNAVAILABLE"), Reply.error(502, "BAD_GATEWAY"), Reply.error(500, "X"));
        for (int i = 0; i < 3; i++) {
            assertThat(events.sync().blocked()).isTrue();
            assertThat(eventCursor()).isEqualTo(cursor);
            assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
            clock.set(clock.instant().plus(Duration.ofMinutes(15)));
        }
        events.sync();
        assertThat(eventCursor()).isEqualTo(cursor + 1);
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("QUARANTINED");
        api.get(facilityManager, "/api/v1/media/" + media + "/content").andExpect(status().isNotFound());
    }

    @Test
    void reviewUnauthorizedAiCheckMustNotConsumeHoldEvent() throws Exception {
        // PHOTO-18: 인증(401)·권한(403) 실패는 AI 공개 상태를 확인하지 못한 것이다. 공개로 보고 지난 알림으로 소비하지 않는다(설정 오류, 커서 유지).
        UUID hold = publishedAfterHoldAndRelease();
        long cursor = eventCursor();
        ai.setHidden(photo, true);
        ai.event("image_hidden", photo, 1, "itda-privacy-review:" + hold);
        ai.nextImage(Reply.error(401, "INVALID_API_KEY"), Reply.error(403, "FORBIDDEN"));
        for (int i = 0; i < 2; i++) {
            AiEventSync.SyncReport report = events.sync();
            assertThat(eventCursor()).as("401·403은 AI 사진 공개를 증명하지 않는다").isEqualTo(cursor);
            assertThat(report.blocked()).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM ai_event_inbox WHERE seq = ? AND provider = ?", String.class, cursor + 1,
                    AiEventSync.PROVIDER)).isEqualTo("RETRY");
            clock.set(clock.instant().plus(Duration.ofMinutes(15)));
        }
        // 설정이 고쳐져 AI가 보류(410)를 알려 주면 그때 업무 사본도 보류한다.
        events.sync();
        assertThat(eventCursor()).isEqualTo(cursor + 1);
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("QUARANTINED");
    }

    @Test
    void aiStateCheckTargetErrorHoldsInsteadOfTreatingAsPublic() throws Exception {
        // PHOTO-18: 대상 오류(404 없음 등)도 공개 확인이 아니다. 이 사진만의 문제로 전체 동기화를 멈추지 않고 보류 쪽(검토 인계)으로 반영한다.
        UUID hold = publishedAfterHoldAndRelease();
        long cursor = eventCursor();
        ai.event("image_hidden", photo, 1, "itda-privacy-review:" + hold);
        ai.nextImage(Reply.error(404, "NOT_FOUND"));
        events.sync();
        assertThat(eventCursor()).isEqualTo(cursor + 1);
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("QUARANTINED");
        reviews.drain();
        assertThat(tasks()).contains("INSPECTION:PRIVACY_HOLD:1");
    }

    @Test
    void remaskEventWithUnauthorizedImageFetchIsNotSkipped() throws Exception {
        // PHOTO-18: 재가림 알림의 새 사본을 401로 못 받으면 건너뛰지 않는다(건너뛰면 누락이 있던 이전 사본이 계속 공개된다).
        events.sync();
        long cursor = eventCursor();
        ai.remask(photo);
        ai.nextImage(Reply.error(401, "INVALID_API_KEY"));
        assertThat(events.sync().blocked()).isTrue();
        assertThat(eventCursor()).isEqualTo(cursor);
        clock.set(clock.instant().plus(Duration.ofMinutes(15)));
        events.sync();
        assertThat(eventCursor()).isEqualTo(cursor + 1);
        assertThat(jdbc.queryForObject("SELECT superseded_by_media_id FROM media WHERE id = ?", UUID.class, media)).isNotNull();
    }

    @Test
    void remaskEventWithMissingImageHoldsCurrentCopy() throws Exception {
        // PHOTO-18: 재가림 알림인데 AI에 사진이 없으면(404) 새 사본을 받을 수 없다. 누락이 있던 지금 사본을 공개로 두지 않는다.
        events.sync();
        long cursor = eventCursor();
        ai.remask(photo);
        ai.nextImage(Reply.error(404, "NOT_FOUND"));
        events.sync();
        assertThat(eventCursor()).isEqualTo(cursor + 1);
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("QUARANTINED");
        api.get(facilityManager, "/api/v1/media/" + media + "/content").andExpect(status().isNotFound());
    }

    @Test
    void lateHoldEventWaitsForQueuedDecisionOfSamePhoto() throws Exception {
        // 경계 B: AI 상태 확인과 DB 반영 사이(또는 직전)에 같은 사진의 새 결정이 보내질 차례면, 그 결과가 정해질 때까지 판단을 미룬다.
        // 담당자가 공개 사진에 '문제 없음'(ok:true)을 다시 결정해 전송 대기 중인데 늦은 보류 알림으로 업무를 보류하면, 곧 그 ok:true가 AI를 해제해
        // AI는 공개·업무는 보류로 어긋난다.
        UUID hold = publishedAfterHoldAndRelease();
        long version = jdbc.queryForObject("SELECT version FROM media WHERE id = ?", Long.class, media);
        ai.setHidden(photo, true);
        ai.event("image_hidden", photo, 1, "itda-privacy-review:" + hold);
        jdbc.update("""
                INSERT INTO privacy_reviews (program_id, media_id, decided_by, ok, reason, media_record_version, decided_media_version,
                    provider_sync_status, next_attempt_at, created_at) VALUES (?, ?, ?, true, '문제 없음 재확인', ?, 1, 'PENDING', ?, ?)""",
                f.program, media, facilityManager, version, java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));
        long cursor = eventCursor();
        assertThat(events.sync().blocked()).isTrue();
        assertThat(eventCursor()).isEqualTo(cursor);
        sender.drain(); // ok:true 전송 → AI 해제
        assertThat(ai.hidden(photo)).isFalse();
        clock.set(clock.instant().plus(Duration.ofMinutes(5)));
        events.sync();
        assertThat(eventCursor()).isEqualTo(cursor + 2); // 늦은 보류 알림(지난 알림으로 판단) + ok:true가 만든 해제 알림
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
    }

    @Test
    void concurrentSyncWorkersApplyEachEventOnce() throws Exception {
        // 경계 C: 여러 동기화 작업이 동시에 돌아도 이벤트는 한 번만 반영되고 오류 없이 끝난다.
        ai.remask(photo);
        ai.hide(photo);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            java.util.List<Future<AiEventSync.SyncReport>> runs = new java.util.ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < 3; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return events.sync();
                }));
            }
            start.countDown();
            for (Future<AiEventSync.SyncReport> run : runs) {
                run.get();
            }
        } finally {
            pool.shutdownNow();
        }
        events.sync();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media WHERE provider_photo_id = ?", Integer.class, photo)).isEqualTo(1 + 0);
        assertThat(jdbc.queryForList("SELECT status FROM ai_event_inbox WHERE provider = ? AND photo_id = ?", String.class, AiEventSync.PROVIDER, photo))
                .containsOnly("APPLIED");
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE provider_photo_id = ? AND superseded_by_media_id IS NULL",
                String.class, photo)).isEqualTo("QUARANTINED");
        assertThat(tasks()).filteredOn(t -> t.contains("PRIVACY_HOLD")).hasSize(1);
    }

    @Test
    void onlyCurrentJurisdictionFacilityManagerDecides() throws Exception {
        api.post(f.manager, path("privacy-reviews"), body(false, 0), UUID.randomUUID()).andExpect(status().isForbidden());
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 하수과");
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", otherDepartment);
        api.post(other, path("privacy-reviews"), body(false, 0), UUID.randomUUID()).andExpect(status().isNotFound());
        api.post(other, "/api/v1/admin/media/" + UUID.randomUUID() + "/privacy-reviews", body(false, 0), UUID.randomUUID())
                .andExpect(status().isNotFound());
        // 시설 대장 관할이 옮겨지면 다음 요청부터 새 부서가 결정한다.
        jdbc.update("UPDATE facilities SET managing_department_id = ? WHERE id = ?", otherDepartment, f.facilities.get(0));
        review(false, 0, UUID.randomUUID()).andExpect(status().isNotFound());
        api.post(other, path("privacy-reviews"), body(false, 0), UUID.randomUUID()).andExpect(status().isOk());
        api.post(other, path("privacy-reviews"), "{\"ok\":false,\"reason\":\" \",\"version\":1}", UUID.randomUUID())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
    }

    private java.util.List<Boolean> sentOks() {
        return ai.auditRequests().stream().map(r -> ((com.fasterxml.jackson.databind.JsonNode) r.get("body")).get("ok").asBoolean()).toList();
    }

    @Test
    void jurisdictionChangedDuringPrivacyReviewMustRejectIt() throws Exception {
        // 같은 패턴(REVIEW-05): 개인정보 판정·재요청도 시설 잠금(1단계) 뒤 현재 관할을 다시 검사한다.
        UUID newDepartment = f.org("DEPARTMENT", null, "가상 새 관리과");
        UUID key = UUID.randomUUID();
        boolean[] waited = new boolean[1];
        int status = duringJurisdictionTransfer(f.facilities.get(0), newDepartment,
                () -> review(false, 0, key).andReturn().getResponse().getStatus(), waited);
        assertThat(waited[0]).as("개인정보 판정 요청이 시설 잠금에서 대기").isTrue();
        assertThat(status).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT privacy_status || ':' || version FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED:0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM privacy_reviews WHERE media_id = ?", Integer.class, media)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'PRIVACY_REVIEWED' AND resource_id = ?", Integer.class,
                media)).isZero();
        assertThat(ai.auditRequests()).isEmpty();
    }

    private ResultActions review(boolean ok, long version, UUID key) throws Exception {
        return api.post(facilityManager, path("privacy-reviews"), body(ok, version), key);
    }

    private ResultActions retry(UUID releaseId, long version, UUID key) throws Exception {
        return api.post(facilityManager, path("privacy-release-retries"), "{\"releaseId\":\"" + releaseId + "\",\"version\":" + version + "}", key);
    }

    private String path(String action) {
        return "/api/v1/admin/media/" + media + "/" + action;
    }

    private static String body(boolean ok, long version) {
        return "{\"ok\":" + ok + ",\"reason\":\"가림 재확인\",\"version\":" + version + "}";
    }

    /** 최신 재승인 처리: 상태:실패사유:무효사유. */
    private String release() {
        return jdbc.queryForObject("""
                SELECT release_status || ':' || COALESCE(failure_reason, '-') || ':' || COALESCE(invalid_reason, '-') FROM privacy_reviews
                WHERE media_id = ? AND release_status IS NOT NULL ORDER BY created_at DESC, id DESC LIMIT 1""", String.class, media);
    }

    private String sync() {
        return jdbc.queryForObject("SELECT provider_sync_status FROM privacy_reviews WHERE media_id = ? ORDER BY created_at LIMIT 1", String.class,
                media);
    }

    @SuppressWarnings("unused")
    private static String read(String body, String path) {
        return JsonPath.read(body, path);
    }
}
