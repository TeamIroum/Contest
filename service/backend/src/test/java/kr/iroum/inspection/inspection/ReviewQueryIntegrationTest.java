package kr.iroum.inspection.inspection;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;

/** 관리자 검토 목록·상세·사진 상세(업무 API 9절, 05단계 REVIEW-01). 현재 관할·역할·AI 원문 비노출·보류 사진 표시. mock AI. */
class ReviewQueryIntegrationTest extends PhotoTestBase {

    UUID roadManager;
    UUID issueInspection;
    UUID failureSlotTask;

    @BeforeEach
    void createTasks() throws Exception {
        roadManager = f.user("정○○");
        f.role(roadManager, "FACILITY_MANAGER", f.department);
        // 장소 ②(DRAIN) 구도 1: AI ok + 이상(덮임) → ISSUE_SUSPECTED. 장소 ③(BENCH): 품질 3회 실패 → QUALITY_RETRY_EXHAUSTED.
        ai.nextAnalyze(Reply.fixture("analyze_02_observe"), Reply.fixture("analyze_09_retake_dark"), Reply.fixture("analyze_08_retake_frame"),
                Reply.fixture("analyze_09_retake_dark"));
        UUID submission = UUID.randomUUID();
        submit(0, 1, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk());
        issueInspection = jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        for (int i = 0; i < 3; i++) {
            clock.set(clock.instant().plusSeconds(1));
            submit(0, 3, UUID.randomUUID(), UUID.randomUUID(), "2026-10-15T01:2" + i + ":30Z", MockAiServer.jpeg(10 + i)).andExpect(status().isOk());
        }
        reviews.drain();
        failureSlotTask = jdbc.queryForObject("SELECT t.id FROM review_tasks t WHERE t.patrol_stop_id = ? AND t.trigger_reason = 'QUALITY_RETRY_EXHAUSTED'",
                UUID.class, p.stops().get(2));
    }

    @Test
    void listIsJurisdictionScopedInReceiptOrderWithoutRiskFields() throws Exception {
        String body = api.get(roadManager, "/api/v1/admin/review-tasks").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertMatches("ReviewTasks", body);
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].triggerReason")).containsExactly("ISSUE_SUSPECTED", "QUALITY_RETRY_EXHAUSTED");
        assertThat(JsonPath.<List<Boolean>>read(body, "$.items[*].hasPhoto")).containsExactly(true, true);
        assertThat(JsonPath.<List<Integer>>read(body, "$.items[*].inspectionCount")).containsExactly(1, 3);
        assertThat(JsonPath.<List<Object>>read(body, "$.items[*].reviewCategory")).containsExactly("REVIEW_REQUIRED", null);
        assertThat(body).doesNotContain("grade", "severity", "priority");
        api.get(roadManager, "/api/v1/admin/review-tasks?triggerReason=QUALITY_RETRY_EXHAUSTED&type=CAPTURE_FAILURE")
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(failureSlotTask.toString()));
        api.get(roadManager, "/api/v1/admin/review-tasks?facilityType=SIDEWALK").andExpect(jsonPath("$.totalElements").value(0));
        api.get(roadManager, "/api/v1/admin/review-tasks?from=2026-10-16").andExpect(jsonPath("$.totalElements").value(0));
        // 다른 부서는 빈 목록·상세 404, 역할 없으면 403. 관할 이관은 다음 요청부터.
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 공원과");
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", otherDepartment);
        api.get(other, "/api/v1/admin/review-tasks").andExpect(jsonPath("$.totalElements").value(0));
        api.get(other, "/api/v1/admin/review-tasks/" + failureSlotTask).andExpect(status().isNotFound());
        api.get(f.manager, "/api/v1/admin/review-tasks").andExpect(status().isForbidden());
        api.get(p.users().get(0), "/api/v1/admin/review-tasks").andExpect(status().isForbidden());
        jdbc.update("UPDATE facilities SET managing_department_id = ? WHERE id = ?", otherDepartment, f.facilities.get(2));
        api.get(other, "/api/v1/admin/review-tasks").andExpect(jsonPath("$.totalElements").value(1));
        api.get(roadManager, "/api/v1/admin/review-tasks/" + failureSlotTask).andExpect(status().isNotFound());
        api.get(other, "/api/v1/admin/review-tasks/" + failureSlotTask).andExpect(status().isOk());
    }

    @Test
    void taskDetailShowsAllowedFindingsAndClassificationButNoAiGrade() throws Exception {
        UUID task = jdbc.queryForObject("SELECT review_task_id FROM review_task_inspections WHERE inspection_id = ?", UUID.class, issueInspection);
        String body = api.get(roadManager, "/api/v1/admin/review-tasks/" + task).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedDecisions[0]").value("ISSUE_CONFIRMED"))
                .andExpect(jsonPath("$.facility.type").value("DRAIN")).andExpect(jsonPath("$.facility.managingDepartment.name").value("가상 도로관리과"))
                .andExpect(jsonPath("$.inspections[0].analysis.status").value("OK"))
                .andExpect(jsonPath("$.inspections[0].analysis.reviewCategory").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.inspections[0].analysis.findings[0].facilityType").value("DRAIN"))
                .andExpect(jsonPath("$.inspections[0].analysis.findings[0].code").value("covered"))
                .andExpect(jsonPath("$.inspections[0].analysis.facilityMatch.matched").value(true))
                .andExpect(jsonPath("$.inspections[0].classification.source").value("AI"))
                .andExpect(jsonPath("$.inspections[0].classification.issueTypes[0]").value("covered"))
                .andExpect(jsonPath("$.inspections[0].capturedBy.members[0].name").value("김○○"))
                .andExpect(jsonPath("$.inspections[0].media.contentPath").value(org.hamcrest.Matchers.startsWith("/api/v1/media/")))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ReviewTask", body);
        assertThat(body).doesNotContain("grade", "severity", "senior_message", "danger_keep_away", "\"action\"", "notes", "/images/");
        // 반복 촬영 실패: 추가 확인·촬영 장애 처리 종료만, 품질 실패 분석은 reviewCategory null.
        String failure = api.get(roadManager, "/api/v1/admin/review-tasks/" + failureSlotTask).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedDecisions").value(org.hamcrest.Matchers.contains("FOLLOW_UP_REQUIRED", "RESOLVE_CAPTURE_PROBLEM")))
                .andExpect(jsonPath("$.inspections.length()").value(3)).andReturn().getResponse().getContentAsString();
        assertMatches("ReviewTask", failure);
        assertThat(JsonPath.<List<Object>>read(failure, "$.inspections[*].analysis.reviewCategory")).containsOnlyNulls();
    }

    @Test
    void adminInspectionSeparatesAiAndHumanAndShowsHeldPhotoReleaseState() throws Exception {
        String body = api.get(roadManager, "/api/v1/admin/inspections/" + issueInspection).andExpect(status().isOk())
                .andExpect(jsonPath("$.classification.ai.facilityType").value("DRAIN"))
                .andExpect(jsonPath("$.classification.current.source").value("AI"))
                .andExpect(jsonPath("$.classification.classificationVersion").value(0))
                .andExpect(jsonPath("$.reviewTaskIds.length()").value(1)).andExpect(jsonPath("$.linkedIssueIds.length()").value(0))
                .andExpect(jsonPath("$.deviceMasked").value(true)).andReturn().getResponse().getContentAsString();
        assertMatches("AdminInspection", body);
        assertThat(body).doesNotContain("grade", "severity", "senior_message");
        // 보류: 사진은 차단(QUARANTINED)으로 보이고, 재승인 전송 실패 중이면 privacyRelease가 처리 상태를 보여 준다. 판정 버튼은 추가 확인만.
        UUID media = JsonPath.<String>read(body, "$.media.id") == null ? null : UUID.fromString(JsonPath.read(body, "$.media.id"));
        api.post(roadManager, "/api/v1/admin/media/" + media + "/privacy-reviews", "{\"ok\":false,\"reason\":\"얼굴 일부 보임\",\"version\":0}",
                UUID.randomUUID()).andExpect(status().isOk());
        ai.nextAudit(Reply.error(503, "UNAVAILABLE"));
        clock.set(clock.instant().plusSeconds(1));
        api.post(roadManager, "/api/v1/admin/media/" + media + "/privacy-reviews", "{\"ok\":true,\"reason\":\"가림 확인\",\"version\":1}",
                UUID.randomUUID()).andExpect(status().isOk());
        String held = api.get(roadManager, "/api/v1/admin/inspections/" + issueInspection).andExpect(status().isOk())
                .andExpect(jsonPath("$.media.privacyStatus").value("QUARANTINED"))
                .andExpect(jsonPath("$.media.privacyRelease.status").value("PENDING"))
                .andExpect(jsonPath("$.captureStatus").value("REVIEW_REQUESTED")).andReturn().getResponse().getContentAsString();
        assertMatches("AdminInspection", held);
        UUID task = jdbc.queryForObject("SELECT review_task_id FROM review_task_inspections WHERE inspection_id = ? LIMIT 1", UUID.class,
                issueInspection);
        String detail = api.get(roadManager, "/api/v1/admin/review-tasks/" + task).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedDecisions").value(org.hamcrest.Matchers.contains("FOLLOW_UP_REQUIRED")))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ReviewTask", detail);
        api.get(roadManager, "/api/v1/media/" + media + "/content").andExpect(status().isNotFound());
        api.get(f.manager, "/api/v1/admin/inspections/" + issueInspection).andExpect(status().isForbidden());
    }
}
