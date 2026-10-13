package kr.iroum.inspection.inspection;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;
import kr.iroum.inspection.support.PatrolFixture;

/**
 * 수리 확인 준비·판정·이력(업무 API 10절, VERIFY-01): 보수 완료 → 재점검 배정 → 재촬영(채택) → 사람 판단. AI 비교는 호출하지 않는다(comparisonStatus null).
 * 지난 회차 409 STALE_VERIFICATION, version·멱등, 증거 조건, 판정별 상태. mock AI.
 */
class VerificationIntegrationTest extends IssueTestBase {

    UUID request;
    UUID repairEvent;
    PatrolFixture.Patrol next;
    UUID slot;
    UUID after;

    @BeforeEach
    void repairedAndRephotographed() throws Exception {
        request = repairedAwaitingVerification();
        repairEvent = jdbc.queryForObject("SELECT repair_event_id FROM reinspection_requests WHERE id = ?", UUID.class, request);
        // 같은 날 오후 출발 전 순회의 재점검 구도에 배정하고 재촬영한다.
        next = f.patrol(2, Instant.parse("2026-10-15T05:00:00Z"), Instant.parse("2026-10-15T07:00:00Z"));
        slot = jdbc.queryForObject("""
                INSERT INTO capture_slots (program_id, patrol_stop_id, sequence_no, label, purpose) VALUES (?, ?, 3, '재점검', 'REINSPECTION') RETURNING id""",
                UUID.class, f.program, next.stops().get(1));
        long patrolVersion = jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, next.id());
        api.post(f.manager, "/api/v1/admin/reinspection-requests/" + request + "/assignments",
                "{\"captureSlotId\":\"" + slot + "\",\"version\":0,\"patrolVersion\":" + patrolVersion + "}", UUID.randomUUID())
                .andExpect(status().isCreated());
        clock.set(Instant.parse("2026-10-15T05:10:00Z"));
        api.post(next.users().get(0), "/api/v1/patrols/" + next.id() + "/activity-starts",
                "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T05:05:00Z\"}").andExpect(status().isOk());
        ai.nextAnalyze(Reply.fixture("analyze_02_observe"));
        UUID submission = UUID.randomUUID();
        mvc.perform(request(next.users().get(0), next.id(), next.stops().get(1), slot, submission, UUID.randomUUID(), "2026-10-15T05:08:00Z",
                MockAiServer.jpeg(42))).andExpect(status().isOk());
        after = jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        assertThat(jdbc.queryForObject("SELECT accepted_inspection_id FROM capture_slots WHERE id = ?", UUID.class, slot)).isEqualTo(after);
    }

    @Test
    void photoVerificationCloseEndsRequestAndIssueWithoutAiComparison() throws Exception {
        String prepared = prepare("PHOTO", "[\"" + inspection + "\"]", "[\"" + after + "\"]", null, 3, 1, UUID.randomUUID())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.comparisonStatus").isEmpty()).andExpect(jsonPath("$.verificationVersion").value(0))
                .andExpect(jsonPath("$.requestVersion").value(2)).andReturn().getResponse().getContentAsString();
        assertMatches("VerificationPreparationResult", prepared);
        UUID verification = UUID.fromString(JsonPath.read(prepared, "$.verificationId"));
        assertThat(ai.analyzeCalls()).as("수리 확인 준비는 AI 비교를 부르지 않는다").isEqualTo(2);
        UUID key = UUID.randomUUID();
        String decided = decide(verification, "CLOSE", null, 3, 2, 0, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.issueStatus").value("CLOSED")).andExpect(jsonPath("$.requestStatus").value("CLOSED"))
                .andExpect(jsonPath("$.verificationVersion").value(1)).andExpect(jsonPath("$.decidedBy.name").value("정○○"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("VerificationDecisionResult", decided);
        decide(verification, "CLOSE", null, 3, 2, 0, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.decidedAt").value(JsonPath.<String>read(decided, "$.decidedAt")));
        assertThat(jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM issues WHERE id = ?", Boolean.class, issue)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reinspection_assignments WHERE request_id = ? AND completed_at IS NOT NULL", Integer.class,
                request)).isEqualTo(1);
        String list = api.get(manager, "/api/v1/admin/issues/" + issue + "/verifications").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].before[0].inspectionId").value(inspection.toString()))
                .andExpect(jsonPath("$.items[0].after[0].inspectionId").value(after.toString()))
                .andExpect(jsonPath("$.items[0].after[0].media.contentPath").value(org.hamcrest.Matchers.startsWith("/api/v1/media/")))
                .andExpect(jsonPath("$.items[0].decision").value("CLOSE")).andExpect(jsonPath("$.items[0].comparisonStatus").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertMatches("Verifications", list);
        assertThat(list).doesNotContain("grade", "severity", "closeCase", "close_case");
        // 끝난 회차로 다시 준비·판정하면 409 STALE_VERIFICATION.
        prepare("PHOTO", "[\"" + inspection + "\"]", "[\"" + after + "\"]", null, 4, 3, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERIFICATION")).andExpect(jsonPath("$.details.currentStatus").value("CLOSED"));
    }

    @Test
    void repairAgainReopensRepairAndOldRoundIsStale() throws Exception {
        UUID verification = verificationId(prepare("SITE_VISIT", "[]", "[]", "현장에서 덮개 다시 확인", 3, 1, UUID.randomUUID())
                .andExpect(status().isCreated()));
        decide(verification, "REPAIR_AGAIN", null, 3, 2, 0, UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.issueStatus").value("REPAIR_REQUESTED")).andExpect(jsonPath("$.requestStatus").value("CLOSED"));
        api.get(manager, "/api/v1/admin/issues/" + issue).andExpect(jsonPath("$.currentReinspectionRequestId").isEmpty())
                .andExpect(jsonPath("$.repairEvents[3].type").value("RE_REQUESTED"))
                .andExpect(jsonPath("$.repairEvents[3].description").value("현장 사진 확인 결과"));
        // 재보수 뒤 착수·완료하면 새 요청이 생기고, 지난 요청으로는 준비할 수 없다.
        repairStart(issueVersion(), UUID.randomUUID()).andExpect(status().isCreated());
        clock.set(clock.instant().plusSeconds(600));
        String done = repairComplete(issueVersion(), clock.instant().toString(), "[]", UUID.randomUUID()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(done, "$.reinspectionRequestId")).isNotEqualTo(request.toString());
        assertMatches("HttpError409", prepare("SITE_VISIT", "[]", "[]", "다시 확인", issueVersion(), 2, UUID.randomUUID()).andReturn().getResponse()
                .getContentAsString());
        prepare("SITE_VISIT", "[]", "[]", "다시 확인", issueVersion(), 2, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERIFICATION"))
                .andExpect(jsonPath("$.details.currentRequestId").value(JsonPath.<String>read(done, "$.reinspectionRequestId")))
                // CONTRACT-01: currentVerificationId는 항상 있고, 대체 준비가 없으면 null.
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasKey("currentVerificationId")))
                .andExpect(jsonPath("$.details.currentVerificationId").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action = 'VERIFICATION_DECIDED'", Integer.class,
                issue)).isEqualTo(1);
    }

    @Test
    void followUpKeepsRequestOpenAndNewPreparationSupersedesOld() throws Exception {
        UUID first = verificationId(prepare("SITE_VISIT", "[]", "[]", "멀리서만 확인", 3, 1, UUID.randomUUID()).andExpect(status().isCreated()));
        UUID second = verificationId(prepare("PHOTO", "[\"" + inspection + "\"]", "[\"" + after + "\"]", null, 3, 2, UUID.randomUUID())
                .andExpect(status().isCreated()));
        // 이전 준비로는 판정할 수 없다(요청 version이 올라감).
        decide(first, "CLOSE", null, 3, 2, 0, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.details.which").value("request"));
        decide(second, "FOLLOW_UP_REQUIRED", "SITE_VISIT", 3, 3, 0, UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.issueStatus").value("AWAITING_VERIFICATION")).andExpect(jsonPath("$.requestStatus").value("OPEN"));
        assertThat(jdbc.queryForObject("SELECT request_type || ':' || status FROM reinspection_requests WHERE id = ?", String.class, request))
                .isEqualTo("SITE_VISIT:OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reinspection_assignments WHERE request_id = ? AND completed_at IS NULL", Integer.class,
                request)).isEqualTo(1);
        decide(second, "CLOSE", null, 4, 4, 1, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.details.which").value("verification"));
    }

    @Test
    void evidenceAndInputAreValidated() throws Exception {
        // PHOTO는 전·후 사진 필수, SITE_VISIT는 확인 내용 필수.
        prepare("PHOTO", "[]", "[\"" + after + "\"]", null, 3, 1, UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("beforeInspectionIds"));
        prepare("SITE_VISIT", "[]", "[]", " ", 3, 1, UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("fieldNote"));
        // BEFORE 자리에 수리 후 사진, AFTER 자리에 문제 판정 사진(배정 구도 아님)은 400.
        prepare("PHOTO", "[\"" + after + "\"]", "[\"" + inspection + "\"]", null, 3, 1, UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("beforeInspectionIds[0]",
                        "afterInspectionIds[0]")));
        // 다른 보수 이벤트·버전 충돌.
        api.post(manager, base() + "/verification-preparations", "{\"requestId\":\"" + request + "\",\"repairEventId\":\"" + UUID.randomUUID()
                + "\",\"verificationMethod\":\"SITE_VISIT\",\"beforeInspectionIds\":[],\"afterInspectionIds\":[],\"fieldNote\":\"확인\",\"version\":3,"
                + "\"requestVersion\":1}", UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERIFICATION"));
        prepare("SITE_VISIT", "[]", "[]", "확인", 2, 1, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.which").value("issue"));
        // AFTER 사진이 준비 뒤 개인정보 보류되면 그 준비로 판단하지 않는다.
        UUID verification = verificationId(prepare("PHOTO", "[\"" + inspection + "\"]", "[\"" + after + "\"]", null, 3, 1, UUID.randomUUID())
                .andExpect(status().isCreated()));
        UUID media = jdbc.queryForObject("SELECT approved_media_id FROM inspections WHERE id = ?", UUID.class, after);
        api.post(manager, "/api/v1/admin/media/" + media + "/privacy-reviews", "{\"ok\":false,\"reason\":\"얼굴 보임\",\"version\":0}", UUID.randomUUID())
                .andExpect(status().isOk());
        decide(verification, "CLOSE", null, 3, 2, 0, UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("verificationId"));
        assertThat(issueStatus()).isEqualTo("AWAITING_VERIFICATION");
        // 다른 부서·다른 역할.
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 공원과");
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", otherDepartment);
        api.get(other, base() + "/verifications").andExpect(status().isNotFound());
        api.get(f.manager, base() + "/verifications").andExpect(status().isForbidden());
    }

    @Test
    void reviewOldPreparationWithLatestRequestVersionMustBeRejected() throws Exception {
        // VERIFY-02: 새 준비가 생기면 이전 준비는 최신 요청 version을 같이 보내도 판정에 쓸 수 없다(서버가 현재 유효한 준비를 확인).
        UUID first = verificationId(prepare("SITE_VISIT", "[]", "[]", "처음 준비", 3, 1, UUID.randomUUID()).andExpect(status().isCreated()));
        UUID second = verificationId(prepare("PHOTO", "[\"" + inspection + "\"]", "[\"" + after + "\"]", null, 3, 2, UUID.randomUUID())
                .andExpect(status().isCreated()));
        assertThat(first).isNotEqualTo(second);
        decide(first, "CLOSE", null, 3, 3, 0, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERIFICATION"))
                .andExpect(jsonPath("$.details.currentVerificationId").value(second.toString()))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("대체")));
        assertThat(issueStatus()).isEqualTo("AWAITING_VERIFICATION");
        assertThat(jdbc.queryForObject("SELECT decision IS NULL FROM verifications WHERE id = ?", Boolean.class, first)).isTrue();
        // 현재 준비로는 판정되고, 같은 키 재시도는 최초 결과를 재현한다.
        UUID key = UUID.randomUUID();
        String decided = decide(second, "CLOSE", null, 3, 3, 0, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        decide(second, "CLOSE", null, 3, 3, 0, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.decidedAt").value(JsonPath.<String>read(decided, "$.decidedAt")));
    }

    @org.springframework.beans.factory.annotation.Autowired
    kr.iroum.inspection.analysis.event.AiEventSync events;

    @Test
    void reviewChangedEvidenceCopyMustRequireNewPreparation() throws Exception {
        // VERIFY-03: 준비 때 검토한 증거 사본(media ID·버전)을 고정한다. 준비 뒤 재가림으로 사본이 바뀌면 그 준비로 판정하지 못하고 새 준비가 필요하다.
        jdbc.update("DELETE FROM ai_event_inbox");
        jdbc.update("DELETE FROM ai_event_cursors");
        UUID verification = verificationId(prepare("PHOTO", "[\"" + inspection + "\"]", "[\"" + after + "\"]", null, 3, 1, UUID.randomUUID())
                .andExpect(status().isCreated()));
        UUID original = jdbc.queryForObject("SELECT approved_media_id FROM inspections WHERE id = ?", UUID.class, after);
        String photo = jdbc.queryForObject("SELECT provider_photo_id FROM media WHERE id = ?", String.class, original);
        ai.remask(photo);
        events.sync();
        UUID fresh = jdbc.queryForObject("SELECT approved_media_id FROM inspections WHERE id = ?", UUID.class, after);
        assertThat(fresh).isNotEqualTo(original);
        decide(verification, "CLOSE", null, 3, 2, 0, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERIFICATION"));
        assertThat(issueStatus()).isEqualTo("AWAITING_VERIFICATION");
        // 새 준비는 새 사본을 고정하고 판정할 수 있다. 이력은 준비마다 당시 사본을 보여 준다.
        UUID renewed = verificationId(prepare("PHOTO", "[\"" + inspection + "\"]", "[\"" + after + "\"]", null, 3, 2, UUID.randomUUID())
                .andExpect(status().isCreated()));
        decide(renewed, "CLOSE", null, 3, 3, 0, UUID.randomUUID()).andExpect(status().isCreated());
        String list = api.get(manager, "/api/v1/admin/issues/" + issue + "/verifications").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].after[0].media.id").value(original.toString()))
                .andExpect(jsonPath("$.items[0].after[0].media.mediaVersion").value(1))
                .andExpect(jsonPath("$.items[1].after[0].media.id").value(fresh.toString()))
                .andExpect(jsonPath("$.items[1].after[0].media.mediaVersion").value(2)).andReturn().getResponse().getContentAsString();
        assertMatches("Verifications", list);
    }

    private ResultActions prepare(String method, String before, String afterIds, String note, long version, long requestVersion, UUID key)
            throws Exception {
        return api.post(manager, base() + "/verification-preparations", "{\"requestId\":\"" + request + "\",\"repairEventId\":\"" + repairEvent
                + "\",\"verificationMethod\":\"" + method + "\",\"beforeInspectionIds\":" + before + ",\"afterInspectionIds\":" + afterIds
                + ",\"fieldNote\":" + (note == null ? "null" : "\"" + note + "\"") + ",\"version\":" + version + ",\"requestVersion\":" + requestVersion
                + "}", key);
    }

    private ResultActions decide(UUID verification, String decision, String followUp, long version, long requestVersion, long verificationVersion,
            UUID key) throws Exception {
        return api.post(manager, base() + "/verification-decisions", "{\"verificationId\":\"" + verification + "\",\"decision\":\"" + decision
                + "\",\"followUpType\":" + (followUp == null ? "null" : "\"" + followUp + "\"") + ",\"reason\":\"현장 사진 확인 결과\",\"version\":"
                + version + ",\"requestVersion\":" + requestVersion + ",\"verificationVersion\":" + verificationVersion + "}", key);
    }

    private static UUID verificationId(ResultActions r) throws Exception {
        return UUID.fromString(JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.verificationId"));
    }

    private String base() {
        return "/api/v1/admin/issues/" + issue;
    }
}
