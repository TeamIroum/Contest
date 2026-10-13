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

import kr.iroum.inspection.support.PatrolFixture;

/** 재점검 요청 목록·배정·취소(업무 API 10절, REINSPECT-01): 역할별 목록, 출발 전 같은 시설 재점검 구도, 한 순회 제한·재배정 취소, version·멱등. mock AI. */
class ReinspectionIntegrationTest extends IssueTestBase {

    UUID request;
    PatrolFixture.Patrol next;
    PatrolFixture.Patrol later;
    UUID nextSlot;
    UUID laterSlot;

    @BeforeEach
    void requestAndUnstartedPatrols() throws Exception {
        request = repairedAwaitingVerification();
        next = f.patrol(2, Instant.parse("2026-10-16T01:00:00Z"), Instant.parse("2026-10-16T03:00:00Z"));
        later = f.patrol(2, Instant.parse("2026-10-17T01:00:00Z"), Instant.parse("2026-10-17T03:00:00Z"));
        nextSlot = reinspectionSlot(next.stops().get(1));
        laterSlot = reinspectionSlot(later.stops().get(1));
    }

    @Test
    void activityAndFacilityManagersListRequestsOfTheirScope() throws Exception {
        String body = api.get(f.manager, "/api/v1/admin/reinspection-requests").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(request.toString()))
                .andExpect(jsonPath("$.items[0].source").value("REPAIR_COMPLETION")).andExpect(jsonPath("$.items[0].requestType").value("REPHOTO"))
                .andExpect(jsonPath("$.items[0].issueId").value(issue.toString())).andExpect(jsonPath("$.items[0].facility.type").value("DRAIN"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ReinspectionRequests", body);
        assertThat(body).doesNotContain("defectCode", "summary", "grade", "severity");
        api.get(manager, "/api/v1/admin/reinspection-requests?status=OPEN").andExpect(jsonPath("$.totalElements").value(1));
        api.get(manager, "/api/v1/admin/reinspection-requests?status=CLOSED").andExpect(jsonPath("$.totalElements").value(0));
        UUID otherAgency = f.org("AGENCY", null, "가상 다른 복지관");
        UUID otherManager = f.user("윤○○");
        f.role(otherManager, "ACTIVITY_MANAGER", otherAgency);
        api.get(otherManager, "/api/v1/admin/reinspection-requests").andExpect(jsonPath("$.totalElements").value(0));
        api.get(f.manager, "/api/v1/admin/reinspection-requests?organizationId=" + otherAgency).andExpect(jsonPath("$.totalElements").value(0));
        api.get(p.users().get(0), "/api/v1/admin/reinspection-requests").andExpect(status().isForbidden());
    }

    @Test
    void assignToUnstartedPatrolAndReassignCancelsOtherPatrol() throws Exception {
        long nextVersion = patrolVersion(next.id());
        long nextRevision = patrolRevision(next.id());
        UUID key = UUID.randomUUID();
        String first = assign(nextSlot, 0, nextVersion, key).andExpect(status().isCreated()).andExpect(jsonPath("$.requestVersion").value(1))
                .andExpect(jsonPath("$.patrolId").value(next.id().toString())).andExpect(jsonPath("$.patrolVersion").value(nextVersion + 1))
                .andExpect(jsonPath("$.cancelledAssignmentIds.length()").value(0)).andReturn().getResponse().getContentAsString();
        assertMatches("ReinspectionAssignmentResult", first);
        assertThat(patrolRevision(next.id())).isEqualTo(nextRevision + 1);
        assign(nextSlot, 0, nextVersion, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignmentId").value(JsonPath.<String>read(first, "$.assignmentId")));
        // 다른 출발 전 순회로 재배정하면 기존 활성 배정은 취소된다(한 요청의 활성 배정은 한 순회).
        clock.set(clock.instant().plusSeconds(60));
        String second = assign(laterSlot, 1, patrolVersion(later.id()), UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.cancelledAssignmentIds[0]").value(JsonPath.<String>read(first, "$.assignmentId")))
                .andReturn().getResponse().getContentAsString();
        assertThat(patrolVersion(next.id())).isEqualTo(nextVersion + 2);
        api.get(manager, "/api/v1/admin/issues/" + issue).andExpect(jsonPath("$.reinspectionRequests[0].assignments.length()").value(2))
                .andExpect(jsonPath("$.reinspectionRequests[0].assignments[0].cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.reinspectionRequests[0].assignments[1].id").value(JsonPath.<String>read(second, "$.assignmentId")))
                .andExpect(jsonPath("$.reinspectionRequests[0].assignments[1].cancelledAt").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action = 'REINSPECTION_ASSIGNED'", Integer.class,
                request)).isEqualTo(2);
    }

    @Test
    void slotPatrolVersionAndRoleAreValidated() throws Exception {
        long v = patrolVersion(next.id());
        // 정기 구도, 다른 시설의 재점검 구도, 없는 구도는 400.
        assign(next.slots().get(1), 0, v, UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("captureSlotId"));
        assign(reinspectionSlot(next.stops().get(0)), 0, v, UUID.randomUUID()).andExpect(status().isBadRequest());
        assign(UUID.randomUUID(), 0, v, UUID.randomUUID()).andExpect(status().isBadRequest());
        assertMatches("HttpError409", assign(nextSlot, 5, v, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.which").value("request")).andExpect(jsonPath("$.details.currentVersion").value(0))
                .andReturn().getResponse().getContentAsString());
        assign(nextSlot, 0, v + 3, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.details.which").value("patrol"));
        // 시설 담당자에게는 배정 권한이 없다(재촬영 일정은 활동 담당자, 업무 API 3절). 다른 기관 활동 담당자는 404.
        api.post(manager, "/api/v1/admin/reinspection-requests/" + request + "/assignments", body(nextSlot, 0, v), UUID.randomUUID())
                .andExpect(status().isForbidden());
        UUID otherAgency = f.org("AGENCY", null, "가상 다른 복지관");
        UUID otherManager = f.user("윤○○");
        f.role(otherManager, "ACTIVITY_MANAGER", otherAgency);
        api.post(otherManager, "/api/v1/admin/reinspection-requests/" + request + "/assignments", body(nextSlot, 0, v), UUID.randomUUID())
                .andExpect(status().isNotFound());
        // 이미 출발한 순회(오늘 순회, 활동 중)의 재점검 구도는 배정할 수 없다.
        UUID startedSlot = reinspectionSlot(p.stops().get(1));
        String departed = assign(startedSlot, 0, patrolVersion(p.id()), UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT")).andExpect(jsonPath("$.details.reason").value("ALREADY_DEPARTED"))
                .andExpect(jsonPath("$.details.activityStatus").value("IN_PROGRESS")).andReturn().getResponse().getContentAsString();
        assertMatches("HttpError409", departed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reinspection_assignments WHERE request_id = ?", Integer.class, request)).isZero();
    }

    @Test
    void cancelBeforeDepartureOnlyWithReason() throws Exception {
        String assigned = assign(nextSlot, 0, patrolVersion(next.id()), UUID.randomUUID()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID assignment = UUID.fromString(JsonPath.read(assigned, "$.assignmentId"));
        String path = "/api/v1/admin/reinspection-assignments/" + assignment + "/cancellations";
        api.post(f.manager, path, "{\"reason\":\" \",\"version\":1,\"patrolVersion\":" + patrolVersion(next.id()) + "}", UUID.randomUUID())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        UUID key = UUID.randomUUID();
        String cancelled = api.post(f.manager, path, "{\"reason\":\"순회 일정 변경\",\"version\":1,\"patrolVersion\":" + patrolVersion(next.id()) + "}",
                key).andExpect(status().isOk()).andExpect(jsonPath("$.requestVersion").value(2)).andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertMatches("ReinspectionCancellationResult", cancelled);
        api.post(f.manager, path, "{\"reason\":\"순회 일정 변경\",\"version\":1,\"patrolVersion\":" + (patrolVersion(next.id()) - 1) + "}", key)
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelledAt").value(JsonPath.<String>read(cancelled, "$.cancelledAt")));
        api.post(f.manager, path, "{\"reason\":\"다시\",\"version\":2,\"patrolVersion\":" + patrolVersion(next.id()) + "}", UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // 출발한 순회의 배정은 취소로 지우지 않는다.
        String again = assign(nextSlot, 2, patrolVersion(next.id()), UUID.randomUUID()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE patrols SET activity_status = 'IN_PROGRESS', departed_at = now() WHERE id = ?", next.id());
        api.post(f.manager, "/api/v1/admin/reinspection-assignments/" + JsonPath.read(again, "$.assignmentId") + "/cancellations",
                "{\"reason\":\"취소\",\"version\":3,\"patrolVersion\":" + patrolVersion(next.id()) + "}", UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("ALREADY_DEPARTED"));
        // 그 상태에서 다른 순회로 옮길 수도 없다(출발한 활성 배정 유지).
        assign(laterSlot, 3, patrolVersion(later.id()), UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("ALREADY_DEPARTED"));
    }

    @Test
    void reviewReassignmentMustNotCancelPatrolOutsideManagerScope() throws Exception {
        // REINSPECT-02: 재배정은 새 순회뿐 아니라 취소될 기존 순회도 담당 범위여야 한다. 범위 밖이면 404이고 아무것도 바뀌지 않는다.
        String first = assign(nextSlot, 0, patrolVersion(next.id()), UUID.randomUUID()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID otherBase = f.org("MEETING_POINT", f.agency, "가상 다른 거점");
        jdbc.update("UPDATE patrols SET base_organization_id = ? WHERE id = ?", otherBase, later.id());
        UUID limited = f.user("윤○○");
        f.role(limited, "ACTIVITY_MANAGER", otherBase);
        api.get(limited, "/api/v1/admin/patrols/" + next.id()).andExpect(status().isNotFound());
        long nextVersion = patrolVersion(next.id());
        long nextRevision = patrolRevision(next.id());
        long laterVersion = patrolVersion(later.id());
        UUID key = UUID.randomUUID();
        api.post(limited, "/api/v1/admin/reinspection-requests/" + request + "/assignments", body(laterSlot, 1, laterVersion), key)
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT cancelled_at IS NULL FROM reinspection_assignments WHERE id = ?", Boolean.class,
                UUID.fromString(JsonPath.read(first, "$.assignmentId")))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reinspection_assignments WHERE request_id = ?", Integer.class, request)).isEqualTo(1);
        assertThat(patrolVersion(next.id())).isEqualTo(nextVersion);
        assertThat(patrolRevision(next.id())).isEqualTo(nextRevision);
        assertThat(patrolVersion(later.id())).isEqualTo(laterVersion);
        assertThat(jdbc.queryForObject("SELECT version FROM reinspection_requests WHERE id = ?", Long.class, request)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action = 'REINSPECTION_ASSIGNED'", Integer.class,
                request)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_requests WHERE user_id = ? AND idempotency_key = ?", Integer.class, limited, key))
                .isZero();
        // 두 순회를 모두 담당하는 기존 담당자는 그대로 재배정할 수 있다.
        assign(laterSlot, 1, laterVersion, UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void reassignmentOrCancellationInvalidatesPreparationWithFactualMessage() throws Exception {
        // CONTRACT-01: 준비 뒤 재배정·배정 취소가 있으면 그 준비로 판정하지 못하고 새 준비가 필요하다. 대체 준비가 없으므로 currentVerificationId는 null,
        // 메시지는 '새 준비로 대체됨'이 아니라 실제 사유(배정 변경)를 말한다.
        UUID repairEvent = jdbc.queryForObject("SELECT repair_event_id FROM reinspection_requests WHERE id = ?", UUID.class, request);
        assign(nextSlot, 0, patrolVersion(next.id()), UUID.randomUUID()).andExpect(status().isCreated());
        UUID first = prepareSiteVisit(repairEvent, 1);
        assign(laterSlot, 2, patrolVersion(later.id()), UUID.randomUUID()).andExpect(status().isCreated());
        assertMatches("HttpError409", decide(first, 3).andReturn().getResponse().getContentAsString());
        decide(first, 3).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERIFICATION"))
                .andExpect(jsonPath("$.details.currentVerificationId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasKey("currentVerificationId")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("배정")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("대체"))));
        // 배정 취소 뒤도 같다.
        UUID second = prepareSiteVisit(repairEvent, 3);
        String active = jdbc.queryForObject("""
                SELECT id::text FROM reinspection_assignments WHERE request_id = ? AND cancelled_at IS NULL AND completed_at IS NULL""", String.class, request);
        api.post(f.manager, "/api/v1/admin/reinspection-assignments/" + active + "/cancellations",
                "{\"reason\":\"일정 변경\",\"version\":4,\"patrolVersion\":" + patrolVersion(later.id()) + "}", UUID.randomUUID()).andExpect(status().isOk());
        decide(second, 5).andExpect(status().isConflict()).andExpect(jsonPath("$.details.currentVerificationId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("대체"))));
        assertThat(issueStatus()).isEqualTo("AWAITING_VERIFICATION");
    }

    private UUID prepareSiteVisit(UUID repairEvent, long requestVersion) throws Exception {
        String body = api.post(manager, "/api/v1/admin/issues/" + issue + "/verification-preparations", "{\"requestId\":\"" + request
                + "\",\"repairEventId\":\"" + repairEvent + "\",\"verificationMethod\":\"SITE_VISIT\",\"beforeInspectionIds\":[],"
                + "\"afterInspectionIds\":[],\"fieldNote\":\"현장 확인\",\"version\":3,\"requestVersion\":" + requestVersion + "}", UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.verificationId"));
    }

    private ResultActions decide(UUID verification, long requestVersion) throws Exception {
        return api.post(manager, "/api/v1/admin/issues/" + issue + "/verification-decisions", "{\"verificationId\":\"" + verification
                + "\",\"decision\":\"CLOSE\",\"followUpType\":null,\"reason\":\"확인\",\"version\":3,\"requestVersion\":" + requestVersion
                + ",\"verificationVersion\":0}", UUID.randomUUID());
    }

    private ResultActions assign(UUID slot, long version, long patrolVersion, UUID key) throws Exception {
        return api.post(f.manager, "/api/v1/admin/reinspection-requests/" + request + "/assignments", body(slot, version, patrolVersion), key);
    }

    private static String body(UUID slot, long version, long patrolVersion) {
        return "{\"captureSlotId\":\"" + slot + "\",\"version\":" + version + ",\"patrolVersion\":" + patrolVersion + "}";
    }

    private UUID reinspectionSlot(UUID stop) {
        return jdbc.queryForObject("""
                INSERT INTO capture_slots (program_id, patrol_stop_id, sequence_no, label, purpose)
                VALUES (?, ?, (SELECT coalesce(max(sequence_no), 0) + 1 FROM capture_slots WHERE patrol_stop_id = ?), '재점검', 'REINSPECTION') RETURNING id""",
                UUID.class, f.program, stop, stop);
    }

    private long patrolVersion(UUID patrol) {
        return jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, patrol);
    }

    private long patrolRevision(UUID patrol) {
        return jdbc.queryForObject("SELECT revision FROM patrols WHERE id = ?", Long.class, patrol);
    }
}
