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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;

/** 분류 정정과 이력(업무 API 9.2절, REVIEW-02): AI 원문 보존·사람 정정 분리, 사전 검증, 버전·동시 정정, 감사, 권한. mock AI. */
class ClassificationIntegrationTest extends PhotoTestBase {

    UUID manager;
    UUID inspection;
    UUID analysis;

    @BeforeEach
    void inspection() throws Exception {
        manager = f.user("정○○");
        f.role(manager, "FACILITY_MANAGER", f.department);
        ai.nextAnalyze(Reply.fixture("analyze_02_observe"));
        UUID submission = UUID.randomUUID();
        submit(0, 1, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk());
        inspection = jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        analysis = jdbc.queryForObject("""
                SELECT r.id FROM analysis_results r JOIN analysis_jobs j ON j.id = r.analysis_job_id WHERE j.inspection_id = ?""", UUID.class, inspection);
    }

    @Test
    void correctionKeepsAiOriginalAddsHistoryAndAudit() throws Exception {
        String aiBefore = jdbc.queryForObject("SELECT result_json::text FROM analysis_results WHERE id = ?", String.class, analysis);
        String ledger = jdbc.queryForObject("SELECT type || managing_department_id FROM facilities WHERE id = ?", String.class, f.facilities.get(1));
        UUID key = UUID.randomUUID();
        String body = correct(key, "DRAIN", "[\"cover_damaged\"]", 0).andExpect(status().isCreated())
                .andExpect(jsonPath("$.previous.source").value("AI")).andExpect(jsonPath("$.previous.issueTypes[0]").value("covered"))
                .andExpect(jsonPath("$.current.source").value("HUMAN")).andExpect(jsonPath("$.current.issueTypes[0]").value("cover_damaged"))
                .andExpect(jsonPath("$.classificationVersion").value(1)).andExpect(jsonPath("$.correctedBy.name").value("정○○"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ClassificationResult", body);
        // 같은 키 재현, 같은 키 다른 내용 409, 지난 버전 409(currentVersion).
        correct(key, "DRAIN", "[\"cover_damaged\"]", 0).andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctionId").value(JsonPath.<String>read(body, "$.correctionId")));
        correct(key, "DRAIN", "[\"cover_missing\"]", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        correct(UUID.randomUUID(), "DRAIN", "[\"cover_missing\"]", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT")).andExpect(jsonPath("$.details.currentVersion").value(1));
        correct(UUID.randomUUID(), "DRAIN", "[\"cover_missing\", \"covered\"]", 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.previous.source").value("HUMAN")).andExpect(jsonPath("$.classificationVersion").value(2));
        // AI 원문·시설 대장·관할은 그대로, 사진 상세는 AI 원본과 현재 사람 분류를 나눠 보여 준다.
        assertThat(jdbc.queryForObject("SELECT result_json::text FROM analysis_results WHERE id = ?", String.class, analysis)).isEqualTo(aiBefore);
        assertThat(jdbc.queryForObject("SELECT type || managing_department_id FROM facilities WHERE id = ?", String.class, f.facilities.get(1)))
                .isEqualTo(ledger);
        api.get(manager, "/api/v1/admin/inspections/" + inspection).andExpect(jsonPath("$.classification.ai.issueTypes[0]").value("covered"))
                .andExpect(jsonPath("$.classification.current.source").value("HUMAN"))
                .andExpect(jsonPath("$.classification.current.issueTypes.length()").value(2))
                .andExpect(jsonPath("$.classification.classificationVersion").value(2));
        String history = api.get(manager, "/api/v1/admin/inspections/" + inspection + "/classification-corrections").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.items[0].previous.source").value("AI"))
                .andExpect(jsonPath("$.items[1].previous.source").value("HUMAN")).andReturn().getResponse().getContentAsString();
        assertMatches("ClassificationHistory", history);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE action = 'CLASSIFICATION_CORRECTED' AND resource_id = ? AND actor_user_id = ?""",
                Integer.class, inspection, manager)).isEqualTo(2);
    }

    @Test
    void dictionaryAndAnalysisLinkAreValidated() throws Exception {
        correct(UUID.randomUUID(), "DRAIN", "[\"lifted\"]", 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("issueTypes"));
        correct(UUID.randomUUID(), "DRAIN", "[\"covered\", \"covered\"]", 0).andExpect(status().isBadRequest());
        correct(UUID.randomUUID(), "ROAD", "[]", 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("facilityType"));
        // 추가 시설 종류는 업무 사전에 문제 코드가 없어(결정 필요) 문제 유형 없이만 정정할 수 있다.
        correct(UUID.randomUUID(), "PARK_EQUIPMENT", "[\"broken\"]", 0).andExpect(status().isBadRequest());
        correct(UUID.randomUUID(), "PARK_EQUIPMENT", "[]", 0).andExpect(status().isCreated());
        api.post(manager, path(), "{\"analysisResultId\":\"" + UUID.randomUUID() + "\",\"facilityType\":\"DRAIN\",\"issueTypes\":[],\"reason\":\"확인\","
                + "\"classificationVersion\":1}", UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("analysisResultId"));
        api.post(manager, path(), "{\"analysisResultId\":\"" + analysis + "\",\"facilityType\":\"DRAIN\",\"issueTypes\":[],\"reason\":\" \","
                + "\"classificationVersion\":1}", UUID.randomUUID()).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentCorrectionsOfSameVersionLetOnlyOneWin() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> runs = new ArrayList<>();
            for (String code : new String[] {"cover_damaged", "cover_missing", "covered"}) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return correct(UUID.randomUUID(), "DRAIN", "[\"" + code + "\"]", 0).andReturn().getResponse().getStatus();
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
        assertThat(jdbc.queryForObject("SELECT classification_version FROM inspections WHERE id = ?", Integer.class, inspection)).isEqualTo(1);
    }

    @Test
    void onlyCurrentJurisdictionFacilityManagerCorrects() throws Exception {
        api.post(f.manager, path(), "{}", UUID.randomUUID()).andExpect(status().isForbidden());
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 공원과");
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", otherDepartment);
        api.post(other, path(), body("DRAIN", "[]", 0), UUID.randomUUID()).andExpect(status().isNotFound());
        api.get(other, path()).andExpect(status().isNotFound());
    }

    @Test
    void jurisdictionChangedDuringCorrectionMustRejectIt() throws Exception {
        // 같은 패턴(REVIEW-05): 분류 정정도 시설 잠금(1단계) 뒤 현재 관할을 다시 검사한다. 이관 커밋 뒤에는 이전 부서 담당자의 정정이 남지 않는다.
        UUID newDepartment = f.org("DEPARTMENT", null, "가상 새 관리과");
        UUID key = UUID.randomUUID();
        boolean[] waited = new boolean[1];
        int status = duringJurisdictionTransfer(f.facilities.get(1), newDepartment,
                () -> correct(key, "DRAIN", "[\"cover_damaged\"]", 0).andReturn().getResponse().getStatus(), waited);
        assertThat(waited[0]).as("정정 요청이 시설 잠금에서 대기").isTrue();
        assertThat(status).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM classification_corrections WHERE inspection_id = ?", Integer.class, inspection)).isZero();
        assertThat(jdbc.queryForObject("SELECT classification_version FROM inspections WHERE id = ?", Integer.class, inspection)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'CLASSIFICATION_CORRECTED' AND resource_id = ?",
                Integer.class, inspection)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_requests WHERE user_id = ? AND idempotency_key = ?", Integer.class,
                manager, key)).isZero();
    }

    private ResultActions correct(UUID key, String type, String codes, int version) throws Exception {
        return api.post(manager, path(), body(type, codes, version), key);
    }

    private String body(String type, String codes, int version) {
        return "{\"analysisResultId\":\"" + analysis + "\",\"facilityType\":\"" + type + "\",\"issueTypes\":" + codes
                + ",\"reason\":\"현장 사진 재확인\",\"classificationVersion\":" + version + "}";
    }

    private String path() {
        return "/api/v1/admin/inspections/" + inspection + "/classification-corrections";
    }
}
