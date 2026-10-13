package kr.iroum.inspection.inspection;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;

/** 06단계 테스트 공통 준비: 빗물받이(장소 ②, 구도 1) 사진을 판정으로 신규 문제(covered)로 만든다. mock AI. */
abstract class IssueTestBase extends PhotoTestBase {

    UUID manager;
    UUID inspection;
    UUID analysis;
    UUID issue;

    @BeforeEach
    void issueFromDecision() throws Exception {
        manager = f.user("정○○");
        f.role(manager, "FACILITY_MANAGER", f.department);
        ai.nextAnalyze(Reply.fixture("analyze_02_observe"));
        UUID submission = UUID.randomUUID();
        submit(0, 1, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk());
        reviews.drain();
        inspection = jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        analysis = jdbc.queryForObject("""
                SELECT r.id FROM analysis_results r JOIN analysis_jobs j ON j.id = r.analysis_job_id WHERE j.inspection_id = ?""", UUID.class, inspection);
        UUID task = jdbc.queryForObject("SELECT review_task_id FROM review_task_inspections WHERE inspection_id = ?", UUID.class, inspection);
        String body = api.post(manager, "/api/v1/admin/review-tasks/" + task + "/decisions", "{\"decision\":\"ISSUE_CONFIRMED\",\"followUpType\":null,"
                + "\"analysisResultId\":\"" + analysis + "\",\"correctionId\":null,\"classificationVersion\":0,\"reason\":\"덮개 위 낙엽 확인\",\"version\":0,"
                + "\"issueResolutions\":[{\"mode\":\"CREATE\",\"inspectionId\":\"" + inspection + "\",\"existingIssueId\":null,\"defectCode\":\"covered\","
                + "\"summary\":\"빗물받이 낙엽 덮임\"}]}", UUID.randomUUID()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        issue = UUID.fromString(JsonPath.read(body, "$.issueIds[0]"));
    }

    protected long issueVersion() {
        return jdbc.queryForObject("SELECT version FROM issues WHERE id = ?", Long.class, issue);
    }

    protected String issueStatus() {
        return jdbc.queryForObject("SELECT status FROM issues WHERE id = ?", String.class, issue);
    }

    protected ResultActions repairRequest(long version, UUID key) throws Exception {
        return api.post(manager, "/api/v1/admin/issues/" + issue + "/repair-requests",
                "{\"assignedDepartmentId\":\"" + f.department + "\",\"memo\":\"덮개 청소 요청\",\"version\":" + version + "}", key);
    }

    protected ResultActions repairStart(long version, UUID key) throws Exception {
        return api.post(manager, "/api/v1/admin/issues/" + issue + "/repair-starts", "{\"version\":" + version + "}", key);
    }

    protected ResultActions repairComplete(long version, String completedAt, String evidence, UUID key) throws Exception {
        return api.post(manager, "/api/v1/admin/issues/" + issue + "/repair-completions", "{\"description\":\"덮개 위 낙엽 제거\",\"completedAt\":\""
                + completedAt + "\",\"evidenceMediaIds\":" + evidence + ",\"version\":" + version + "}", key);
    }

    /** 보수 증거 사진 행을 직접 만든다(업로드 API는 RepairEvidenceIntegrationTest). REPAIR_EVIDENCE는 이 문제로 올린 사진으로 둔다. */
    protected UUID evidenceMedia(String purpose, String privacyStatus) {
        return jdbc.queryForObject("""
                INSERT INTO media (program_id, uploaded_by, bucket, object_key, purpose, privacy_status, content_type, size_bytes, sha256, issue_id)
                VALUES (?, ?, 'inspection-media', ?, ?, ?, 'image/jpeg', 100, decode(repeat('ab', 32), 'hex'), ?) RETURNING id""", UUID.class,
                f.program, manager, "test/evidence/" + UUID.randomUUID() + ".jpg", purpose, privacyStatus,
                "REPAIR_EVIDENCE".equals(purpose) ? issue : null);
    }

    /** 요청 → 착수 → 완료까지 진행하고 재점검 요청 ID를 돌려준다. */
    protected UUID repairedAwaitingVerification() throws Exception {
        repairRequest(issueVersion(), UUID.randomUUID()).andExpect(status().isCreated());
        repairStart(issueVersion(), UUID.randomUUID()).andExpect(status().isCreated());
        clock.set(clock.instant().plusSeconds(3600));
        String body = repairComplete(issueVersion(), clock.instant().toString(), "[]", UUID.randomUUID()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.reinspectionRequestId"));
    }
}
