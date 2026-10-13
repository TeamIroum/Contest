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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;

/** 사람 판정(업무 API 9.1절, D-25·26, REVIEW-03): 신규·기존 연결, 한 트랜잭션, 버전·동시 판정, 반복 실패 처리, 권한·누출. mock AI. */
class DecisionIntegrationTest extends PhotoTestBase {

    UUID manager;
    UUID inspection;
    UUID analysis;
    UUID task;
    UUID failureTask;

    @BeforeEach
    void createTasks() throws Exception {
        manager = f.user("정○○");
        f.role(manager, "FACILITY_MANAGER", f.department);
        ai.nextAnalyze(Reply.fixture("analyze_02_observe"), Reply.fixture("analyze_09_retake_dark"), Reply.fixture("analyze_09_retake_dark"),
                Reply.fixture("analyze_09_retake_dark"));
        UUID submission = UUID.randomUUID();
        submit(0, 1, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk());
        for (int i = 0; i < 3; i++) {
            submit(0, 3, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:2" + (i + 1) + ":00Z", MockAiServer.jpeg(5 + i)).andExpect(status().isOk());
        }
        reviews.drain();
        inspection = jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        analysis = jdbc.queryForObject("""
                SELECT r.id FROM analysis_results r JOIN analysis_jobs j ON j.id = r.analysis_job_id WHERE j.inspection_id = ?""", UUID.class, inspection);
        task = jdbc.queryForObject("SELECT review_task_id FROM review_task_inspections WHERE inspection_id = ?", UUID.class, inspection);
        failureTask = jdbc.queryForObject("SELECT id FROM review_tasks WHERE patrol_stop_id = ? AND type = 'CAPTURE_FAILURE'", UUID.class,
                p.stops().get(2));
    }

    @Test
    void issueConfirmedCreatesAndLinksIssuesInOneTransaction() throws Exception {
        UUID open = issue("OPEN", "covered", "북쪽 덮개 낙엽");
        long openVersion = jdbc.queryForObject("SELECT version FROM issues WHERE id = ?", Long.class, open);
        // 정정 뒤 정정 근거로 판정한다(작업 version은 정정으로 오르지 않음).
        String correction = api.post(manager, "/api/v1/admin/inspections/" + inspection + "/classification-corrections",
                "{\"analysisResultId\":\"" + analysis + "\",\"facilityType\":\"DRAIN\",\"issueTypes\":[\"cover_damaged\"],\"reason\":\"파손 보임\","
                        + "\"classificationVersion\":0}", UUID.randomUUID()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String correctionId = JsonPath.read(correction, "$.correctionId");
        UUID key = UUID.randomUUID();
        String request = decision("ISSUE_CONFIRMED", null, analysis, correctionId, 1, 0, "[" + create("cover_damaged", "남쪽 덮개 금")
                + "," + link(open) + "," + create("cover_damaged", "동쪽 덮개 모서리 깨짐") + "]");
        String body = api.post(manager, path(task), request, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewTaskStatus").value("RESOLVED")).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.issueResults.length()").value(3)).andExpect(jsonPath("$.issueResults[1].issueId").value(open.toString()))
                .andExpect(jsonPath("$.issueResults[1].defectCode").value("covered")).andExpect(jsonPath("$.issueIds.length()").value(3))
                .andExpect(jsonPath("$.decidedBy.name").value("정○○")).andReturn().getResponse().getContentAsString();
        assertMatches("DecisionResult", body);
        // 같은 키 재현(중복 문제 없음), 기존 문제는 상태·설명·version 그대로.
        api.post(manager, path(task), request, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewId").value(JsonPath.<String>read(body, "$.reviewId")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issues WHERE facility_id = ?", Integer.class, f.facilities.get(1))).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status || ':' || summary || ':' || version FROM issues WHERE id = ?", String.class, open))
                .isEqualTo("OPEN:북쪽 덮개 낙엽:" + openVersion);
        String detail = api.get(manager, "/api/v1/admin/review-tasks/" + task).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED")).andExpect(jsonPath("$.allowedDecisions.length()").value(0))
                .andExpect(jsonPath("$.decisions[0].correctionId").value(correctionId)).andExpect(jsonPath("$.decisions[0].classificationVersion").value(1))
                .andExpect(jsonPath("$.openIssues.length()").value(3)).andReturn().getResponse().getContentAsString();
        assertMatches("ReviewTask", detail);
        api.get(manager, "/api/v1/admin/inspections/" + inspection).andExpect(jsonPath("$.linkedIssueIds.length()").value(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'REVIEW_DECIDED' AND resource_id = ?", Integer.class, task))
                .isEqualTo(1);
        // 끝난 작업에 새 판정은 409.
        api.post(manager, path(task), decision("NO_ISSUE", null, null, null, null, 1, "[]"), UUID.randomUUID()).andExpect(status().isConflict());
    }

    @Test
    void closedLinkTargetOrBadCodeRollsBackEverything() throws Exception {
        UUID closed = issue("CLOSED", "covered", "지난 문제");
        String notLinkable = api.post(manager, path(task), decision("ISSUE_CONFIRMED", null, analysis, null, 0, 0, "[" + create("covered", "새 문제")
                + "," + link(closed) + "]"), UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ISSUE_NOT_LINKABLE"))
                .andExpect(jsonPath("$.details.currentStatus").value("CLOSED")).andReturn().getResponse().getContentAsString();
        assertMatches("ErrorIssueNotLinkable", notLinkable);
        api.post(manager, path(task), decision("ISSUE_CONFIRMED", null, analysis, null, 0, 0, "[" + create("lifted", "보도 코드") + "]"),
                UUID.randomUUID()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("issueResolutions[0].defectCode"));
        UUID otherFacilityIssue = jdbc.queryForObject("""
                INSERT INTO issues (program_id, facility_id, defect_code, summary, assigned_department_id) VALUES (?, ?, 'lifted', '다른 시설', ?) RETURNING id""",
                UUID.class, f.program, f.facilities.get(0), f.department);
        api.post(manager, path(task), decision("ISSUE_CONFIRMED", null, analysis, null, 0, 0, "[" + link(otherFacilityIssue) + "]"),
                UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issues WHERE facility_id = ?", Integer.class, f.facilities.get(1))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_decisions WHERE review_task_id = ?", Integer.class, task)).isZero();
        assertThat(jdbc.queryForObject("SELECT status || version FROM review_tasks WHERE id = ?", String.class, task)).isEqualTo("OPEN0");
    }

    @Test
    void staleClassificationOrTaskVersionAndConcurrentDecisionsConflict() throws Exception {
        api.post(manager, "/api/v1/admin/inspections/" + inspection + "/classification-corrections", "{\"analysisResultId\":\"" + analysis
                + "\",\"facilityType\":\"DRAIN\",\"issueTypes\":[],\"reason\":\"이상 없어 보임\",\"classificationVersion\":0}", UUID.randomUUID())
                .andExpect(status().isCreated());
        String conflict = api.post(manager, path(task), decision("NO_ISSUE", null, analysis, null, 0, 0, "[]"), UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.currentVersion").value(1)).andReturn().getResponse()
                .getContentAsString();
        assertMatches("ErrorVersionConflict", conflict);
        api.post(manager, path(task), decision("NO_ISSUE", null, null, null, null, 7, "[]"), UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.currentVersion").value(0));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> runs = new ArrayList<>();
            for (String d : new String[] {"NO_ISSUE", "FOLLOW_UP_REQUIRED", "NO_ISSUE"}) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return api.post(manager, path(task), decision(d, d.equals("FOLLOW_UP_REQUIRED") ? "SITE_VISIT" : null, null, null, null, 0, "[]"),
                            UUID.randomUUID()).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> r : runs) {
                statuses.add(r.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_decisions WHERE review_task_id = ?", Integer.class, task)).isEqualTo(1);
    }

    @Test
    void captureFailureAllowsFollowUpOrCaptureProblemOnly() throws Exception {
        api.post(manager, path(failureTask), decision("NO_ISSUE", null, null, null, null, 0, "[]"), UUID.randomUUID())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("decision"));
        String body = api.post(manager, path(failureTask), decision("FOLLOW_UP_REQUIRED", "REPHOTO", null, null, null, 0, "[]"), UUID.randomUUID())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.followUpType").value("REPHOTO"))
                .andExpect(jsonPath("$.reinspectionRequestId").isNotEmpty()).andReturn().getResponse().getContentAsString();
        assertMatches("DecisionResult", body);
        assertThat(jdbc.queryForObject("SELECT request_type || status FROM reinspection_requests WHERE id = ?::uuid", String.class,
                (Object) JsonPath.read(body, "$.reinspectionRequestId"))).isEqualTo("REPHOTOOPEN");
    }

    @Test
    void invalidRequestsHeldPhotoAndRolesAreRejected() throws Exception {
        api.post(manager, path(task), decision("ISSUE_CONFIRMED", null, analysis, null, 0, 0, "[]"), UUID.randomUUID()).andExpect(status().isBadRequest());
        api.post(manager, path(task), decision("NO_ISSUE", "REPHOTO", null, null, null, 0, "[]"), UUID.randomUUID()).andExpect(status().isBadRequest());
        api.post(manager, path(task), decision("NO_ISSUE", null, analysis, null, 1, 0, "[]"), UUID.randomUUID()).andExpect(status().isBadRequest());
        api.post(manager, path(task), decision("RESOLVE_CAPTURE_PROBLEM", null, null, null, null, 0, "[]"), UUID.randomUUID())
                .andExpect(status().isBadRequest());
        // 보류된 사진은 이상 확인·이상 없음 불가, 추가 확인은 가능.
        UUID media = jdbc.queryForObject("SELECT approved_media_id FROM inspections WHERE id = ?", UUID.class, inspection);
        api.post(manager, "/api/v1/admin/media/" + media + "/privacy-reviews", "{\"ok\":false,\"reason\":\"얼굴 보임\",\"version\":0}", UUID.randomUUID())
                .andExpect(status().isOk());
        api.post(manager, path(task), decision("NO_ISSUE", null, null, null, null, 0, "[]"), UUID.randomUUID()).andExpect(status().isBadRequest());
        api.post(manager, path(task), decision("FOLLOW_UP_REQUIRED", "SITE_VISIT", null, null, null, 0, "[]"), UUID.randomUUID())
                .andExpect(status().isCreated());
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", f.org("DEPARTMENT", null, "가상 공원과"));
        api.post(other, path(failureTask), decision("FOLLOW_UP_REQUIRED", "REPHOTO", null, null, null, 0, "[]"), UUID.randomUUID())
                .andExpect(status().isNotFound());
        api.post(f.manager, path(failureTask), "{}", UUID.randomUUID()).andExpect(status().isForbidden());
        // 시니어 응답에는 시설 이상 근거·AI 등급이 없다.
        String sync = api.get(p.users().get(0), "/api/v1/patrols/" + p.id() + "/sync-state").andReturn().getResponse().getContentAsString();
        String detail = api.get(p.users().get(0), "/api/v1/patrols/" + p.id()).andReturn().getResponse().getContentAsString();
        String capture = api.get(p.users().get(0), "/api/v1/inspections/" + inspection + "/capture-status").andReturn().getResponse()
                .getContentAsString();
        for (String b : List.of(sync, detail, capture)) {
            assertThat(b).doesNotContain("covered", "낙엽", "grade", "severity", "findings", "evidence", "reviewCategory");
        }
    }

    @Test
    void reviewJurisdictionChangedWhileWaitingForFacilityLockMustRejectDecision() throws Exception {
        // 관할 이관이 시설 행을 잠근 동안 이전 부서 담당자의 판정이 대기했다가 이관 커밋 뒤 진행하면, 잠금 획득 후 현재 관할로 다시 검사해 404.
        UUID newDepartment = f.org("DEPARTMENT", null, "가상 새 관리과");
        UUID key = UUID.randomUUID();
        boolean[] waited = new boolean[1];
        int status = duringJurisdictionTransfer(f.facilities.get(1), newDepartment, () -> api.post(manager, path(task),
                decision("ISSUE_CONFIRMED", null, analysis, null, 0, 0, "[" + create("covered", "이관 중 판정") + "]"), key)
                .andReturn().getResponse().getStatus(), waited);
        assertThat(waited[0]).as("판정 요청이 시설 잠금에서 대기").isTrue();
        assertThat(status).as("이관 뒤 이전 부서 담당자는 판정할 수 없다").isEqualTo(404);
        // 판정·문제·증거 연결·재점검 요청·작업 완료·감사·outbox·멱등 결과가 하나도 남지 않는다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_decisions WHERE review_task_id = ?", Integer.class, task)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issues WHERE facility_id = ?", Integer.class, f.facilities.get(1))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issue_inspections WHERE inspection_id = ?", Integer.class, inspection)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reinspection_requests WHERE facility_id = ?", Integer.class, f.facilities.get(1))).isZero();
        assertThat(jdbc.queryForObject("SELECT status || ':' || version FROM review_tasks WHERE id = ?", String.class, task)).isEqualTo("OPEN:0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'REVIEW_DECIDED' AND actor_user_id = ?", Integer.class,
                manager)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'REVIEW_DECIDED' AND aggregate_id = ?", Integer.class,
                task)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_requests WHERE user_id = ? AND idempotency_key = ?", Integer.class,
                manager, key)).isZero();
        // 새 관할 담당자는 그대로 판정할 수 있다.
        UUID successor = f.user("한○○");
        f.role(successor, "FACILITY_MANAGER", newDepartment);
        api.post(successor, path(task), decision("NO_ISSUE", null, null, null, null, 0, "[]"), UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void reviewPrivacyReapprovalMustAllowHumanJudgmentWithoutReadoptingSlot() throws Exception {
        // REVIEW-06: 개인정보 보류 → 재승인 완료 뒤 현재 사본은 APPROVED(조회 200)인데 구도 채택 포인터는 복원되지 않는다(D-28).
        // 판정 가능 여부는 '개인정보 승인된 현재 사본'으로 보고, 구도 채택·활동 상태·이전 판정은 자동 복원하지 않는다.
        UUID media = jdbc.queryForObject("SELECT approved_media_id FROM inspections WHERE id = ?", UUID.class, inspection);
        String privacy = "/api/v1/admin/media/" + media + "/privacy-reviews";
        api.post(manager, privacy, "{\"ok\":false,\"reason\":\"가림 확인\",\"version\":0}", UUID.randomUUID()).andExpect(status().isOk());
        // 보류 중에는 추가 확인만 가능하다.
        api.get(manager, "/api/v1/admin/review-tasks/" + task)
                .andExpect(jsonPath("$.allowedDecisions").value(org.hamcrest.Matchers.contains("FOLLOW_UP_REQUIRED")));
        api.post(manager, path(task), decision("NO_ISSUE", null, null, null, null, 0, "[]"), UUID.randomUUID()).andExpect(status().isBadRequest());
        api.post(manager, privacy, "{\"ok\":true,\"reason\":\"가림 확인\",\"version\":1}", UUID.randomUUID()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        api.get(manager, "/api/v1/media/" + media + "/content").andExpect(status().isOk());
        String slotBefore = jdbc.queryForObject("""
                SELECT coalesce(s.accepted_inspection_id::text, '-') || ':' || s.resolution || ':' || i.capture_status || ':'
                    || coalesce(i.approved_media_id::text, '-') FROM capture_slots s JOIN inspections i ON i.capture_slot_id = s.id WHERE i.id = ?""",
                String.class, inspection);
        assertThat(slotBefore).isEqualTo("-:REVIEW_REQUESTED:REVIEW_REQUESTED:-");
        String detail = api.get(manager, "/api/v1/admin/review-tasks/" + task).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedDecisions").value(org.hamcrest.Matchers.contains("ISSUE_CONFIRMED", "NO_ISSUE", "FOLLOW_UP_REQUIRED")))
                .andExpect(jsonPath("$.inspections[0].media.id").value(media.toString()))
                .andExpect(jsonPath("$.inspections[0].media.privacyStatus").value("APPROVED")).andReturn().getResponse().getContentAsString();
        assertMatches("ReviewTask", detail);
        long version = JsonPath.<Integer>read(detail, "$.version");
        api.post(manager, path(task), decision("ISSUE_CONFIRMED", null, analysis, null, 0, version, "[" + create("covered", "가림 뒤 확인") + "]"),
                UUID.randomUUID()).andExpect(status().isCreated()).andExpect(jsonPath("$.issueResults[0].defectCode").value("covered"));
        // 판정은 구도 채택·사진 시도 상태·승인 포인터를 되살리지 않는다.
        assertThat(jdbc.queryForObject("""
                SELECT coalesce(s.accepted_inspection_id::text, '-') || ':' || s.resolution || ':' || i.capture_status || ':'
                    || coalesce(i.approved_media_id::text, '-') FROM capture_slots s JOIN inspections i ON i.capture_slot_id = s.id WHERE i.id = ?""",
                String.class, inspection)).isEqualTo(slotBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issue_inspections WHERE inspection_id = ?", Integer.class, inspection)).isEqualTo(1);
    }

    private UUID issue(String status, String code, String summary) {
        return jdbc.queryForObject("""
                INSERT INTO issues (program_id, facility_id, defect_code, summary, status, assigned_department_id, closed_at)
                VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? = 'CLOSED' THEN now() END) RETURNING id""", UUID.class, f.program, f.facilities.get(1), code,
                summary, status, f.department, status);
    }

    private String create(String code, String summary) {
        return "{\"mode\":\"CREATE\",\"inspectionId\":\"" + inspection + "\",\"existingIssueId\":null,\"defectCode\":\"" + code + "\",\"summary\":\""
                + summary + "\"}";
    }

    private String link(UUID issue) {
        return "{\"mode\":\"LINK\",\"inspectionId\":\"" + inspection + "\",\"existingIssueId\":\"" + issue + "\",\"defectCode\":null,\"summary\":null}";
    }

    private static String decision(String decision, String followUp, UUID analysis, String correction, Integer version, long taskVersion,
            String resolutions) {
        return "{\"decision\":\"" + decision + "\",\"followUpType\":" + (followUp == null ? "null" : "\"" + followUp + "\"")
                + ",\"analysisResultId\":" + (analysis == null ? "null" : "\"" + analysis + "\"") + ",\"correctionId\":"
                + (correction == null ? "null" : "\"" + correction + "\"") + ",\"classificationVersion\":" + version
                + ",\"reason\":\"현장 사진 확인\",\"version\":" + taskVersion + ",\"issueResolutions\":" + resolutions + "}";
    }

    private static String path(UUID task) {
        return "/api/v1/admin/review-tasks/" + task + "/decisions";
    }
}
