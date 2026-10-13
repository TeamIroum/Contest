package kr.iroum.inspection.inspection;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

/** 보수 요청·착수·완료(업무 API 10절, REPAIR-01): 상태 전이·version·멱등, 완료와 재점검 요청의 한 트랜잭션, 증거 사진·담당 부서 검증, 권한·관할 경합. mock AI. */
class RepairIntegrationTest extends IssueTestBase {

    @Test
    void requestStartCompleteCreatesReinspectionRequestInOneTransaction() throws Exception {
        UUID key = UUID.randomUUID();
        String requested = repairRequest(0, key).andExpect(status().isCreated()).andExpect(jsonPath("$.issueStatus").value("REPAIR_REQUESTED"))
                .andExpect(jsonPath("$.issueVersion").value(1)).andExpect(jsonPath("$.reinspectionRequestId").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertMatches("RepairResult", requested);
        // 같은 키 재현, 같은 키 다른 내용 409.
        repairRequest(0, key).andExpect(status().isCreated()).andExpect(jsonPath("$.repairEventId").value(JsonPath.<String>read(requested, "$.repairEventId")));
        api.post(manager, "/api/v1/admin/issues/" + issue + "/repair-requests", "{\"assignedDepartmentId\":\"" + f.department
                + "\",\"memo\":\"다른 메모\",\"version\":0}", key).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        repairStart(1, UUID.randomUUID()).andExpect(status().isCreated()).andExpect(jsonPath("$.issueStatus").value("IN_REPAIR"));
        clock.set(clock.instant().plusSeconds(3600));
        UUID photo = evidenceMedia("REPAIR_EVIDENCE", "APPROVED");
        String done = repairComplete(2, clock.instant().toString(), "[\"" + photo + "\"]", UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.issueStatus").value("AWAITING_VERIFICATION")).andExpect(jsonPath("$.issueVersion").value(3))
                .andReturn().getResponse().getContentAsString();
        assertMatches("RepairResult", done);
        UUID request = UUID.fromString(JsonPath.read(done, "$.reinspectionRequestId"));
        assertThat(jdbc.queryForObject("""
                SELECT r.request_type || ':' || r.status || ':' || (r.repair_event_id = ?::uuid) || ':' || (i.current_reinspection_request_id = r.id)
                FROM reinspection_requests r JOIN issues i ON i.id = r.issue_id WHERE r.id = ?""", String.class,
                JsonPath.<String>read(done, "$.repairEventId"), request)).isEqualTo("REPHOTO:OPEN:true:true");
        String detail = api.get(manager, "/api/v1/admin/issues/" + issue).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_VERIFICATION"))
                .andExpect(jsonPath("$.repairEvents[*].type").value(org.hamcrest.Matchers.contains("REQUESTED", "STARTED", "COMPLETED")))
                .andExpect(jsonPath("$.repairEvents[0].description").value("덮개 청소 요청"))
                .andExpect(jsonPath("$.repairEvents[2].evidenceMedia[0].id").value(photo.toString()))
                .andExpect(jsonPath("$.reinspectionRequests[0].id").value(request.toString()))
                .andExpect(jsonPath("$.currentReinspectionRequestId").value(request.toString())).andReturn().getResponse().getContentAsString();
        assertMatches("Issue", detail);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action LIKE 'REPAIR_%'", Integer.class, issue))
                .isEqualTo(3);
    }

    @Test
    void wrongStateStaleVersionAndTimesAreRejectedWithoutChanges() throws Exception {
        assertMatches("HttpError409", repairStart(0, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUE_STATE_CONFLICT")).andExpect(jsonPath("$.details.currentStatus").value("OPEN"))
                .andExpect(jsonPath("$.details.issueId").value(issue.toString())).andReturn().getResponse().getContentAsString());
        assertMatches("HttpError409", repairRequest(3, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT")).andExpect(jsonPath("$.details.currentVersion").value(0))
                .andExpect(jsonPath("$.details.which").value("issue")).andReturn().getResponse().getContentAsString());
        repairRequest(0, UUID.randomUUID()).andExpect(status().isCreated());
        repairRequest(1, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ISSUE_STATE_CONFLICT"));
        repairComplete(1, clock.instant().toString(), "[]", UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.currentStatus").value("REPAIR_REQUESTED"));
        repairStart(1, UUID.randomUUID()).andExpect(status().isCreated());
        // 미래 시각·착수 전 시각·필수값 누락은 400, 아무것도 바뀌지 않는다.
        repairComplete(2, clock.instant().plusSeconds(3600).toString(), "[]", UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("completedAt"));
        repairComplete(2, clock.instant().minusSeconds(3600).toString(), "[]", UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("completedAt"));
        api.post(manager, "/api/v1/admin/issues/" + issue + "/repair-completions", "{\"description\":\" \",\"completedAt\":null,"
                + "\"evidenceMediaIds\":null,\"version\":2}", UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(3));
        assertThat(issueStatus()).isEqualTo("IN_REPAIR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reinspection_requests WHERE issue_id = ?", Integer.class, issue)).isZero();
    }

    @Test
    void evidenceAndDepartmentAreValidated() throws Exception {
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 공원과");
        api.post(manager, "/api/v1/admin/issues/" + issue + "/repair-requests", "{\"assignedDepartmentId\":\"" + otherDepartment
                + "\",\"memo\":null,\"version\":0}", UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("assignedDepartmentId"));
        api.post(manager, "/api/v1/admin/issues/" + issue + "/repair-requests", "{\"assignedDepartmentId\":\"" + UUID.randomUUID()
                + "\",\"memo\":null,\"version\":0}", UUID.randomUUID()).andExpect(status().isBadRequest());
        repairRequest(0, UUID.randomUUID()).andExpect(status().isCreated());
        repairStart(1, UUID.randomUUID()).andExpect(status().isCreated());
        String now = clock.instant().toString();
        UUID inspectionPhoto = jdbc.queryForObject("SELECT media_id FROM inspections WHERE id = ?", UUID.class, inspection);
        for (UUID wrong : List.of(inspectionPhoto, evidenceMedia("REPAIR_EVIDENCE", "QUARANTINED"), evidenceMedia("REFERENCE", "APPROVED"),
                UUID.randomUUID())) {
            repairComplete(2, now, "[\"" + wrong + "\"]", UUID.randomUUID()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("evidenceMediaIds[0]"));
        }
        UUID ok = evidenceMedia("REPAIR_EVIDENCE", "APPROVED");
        repairComplete(2, now, "[\"" + ok + "\",\"" + ok + "\"]", UUID.randomUUID()).andExpect(status().isBadRequest());
        repairComplete(2, now, "[\"" + ok + "\"]", UUID.randomUUID()).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_event_media WHERE media_id = ?", Integer.class, ok)).isEqualTo(1);
    }

    @Test
    void onlyCurrentJurisdictionFacilityManagerAndOneOfConcurrentRequestsWins() throws Exception {
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 공원과");
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", otherDepartment);
        api.post(other, "/api/v1/admin/issues/" + issue + "/repair-starts", "{\"version\":0}", UUID.randomUUID()).andExpect(status().isNotFound());
        api.post(f.manager, "/api/v1/admin/issues/" + issue + "/repair-starts", "{\"version\":0}", UUID.randomUUID()).andExpect(status().isForbidden());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return repairRequest(0, UUID.randomUUID()).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> run : runs) {
                statuses.add(run.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_events WHERE issue_id = ?", Integer.class, issue)).isEqualTo(1);
    }

    @Test
    void jurisdictionTransferWhileWaitingRejectsRepairRequest() throws Exception {
        UUID newDepartment = f.org("DEPARTMENT", null, "가상 새 관리과");
        boolean[] waited = new boolean[1];
        int result = duringJurisdictionTransfer(f.facilities.get(1), newDepartment,
                () -> repairRequest(0, UUID.randomUUID()).andReturn().getResponse().getStatus(), waited);
        assertThat(waited[0]).isTrue();
        assertThat(result).isEqualTo(404);
        assertThat(issueStatus()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_events WHERE issue_id = ?", Integer.class, issue)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action LIKE 'REPAIR_%'", Integer.class, issue))
                .isZero();
    }
}
