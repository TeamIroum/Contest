package kr.iroum.inspection.inspection;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;

/**
 * 보수 증거 사진 업로드(업무 API 10절 POST /admin/media, MEDIA-UP-01): AI /privacy/mask 검사 완료본만 저장, 문제 연결, 조회 권한, 보수 완료 증거 사용,
 * 멱등, AI 장애·마스킹 실패·상태 변경 시 저장 사본 정리. mock AI.
 */
class RepairEvidenceIntegrationTest extends IssueTestBase {

    static final String REPORT = "{\"faces\":0,\"plates\":1,\"vehicles\":0,\"detector\":\"yunet-web\",\"appVersion\":\"0.3.0\","
            + "\"boxes\":[{\"kind\":\"plate\",\"box\":[0.1,0.2,0.3,0.4]}]}";

    @Test
    void uploadStoresOnlyMaskedCopyLinkedToIssueAndUsableAsRepairEvidence() throws Exception {
        repairRequest(0, UUID.randomUUID()).andExpect(status().isCreated());
        repairStart(1, UUID.randomUUID()).andExpect(status().isCreated());
        UUID key = UUID.randomUUID();
        String body = upload(manager, "REPAIR_EVIDENCE", issue, "true", MockAiServer.jpeg(77), key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("REPAIR_EVIDENCE")).andExpect(jsonPath("$.privacyStatus").value("APPROVED"))
                .andExpect(jsonPath("$.issueId").value(issue.toString())).andReturn().getResponse().getContentAsString();
        assertMatches("RepairEvidenceResult", body);
        UUID media = UUID.fromString(JsonPath.read(body, "$.mediaId"));
        // AI에는 media_id = 업무 media ID, purpose=repair_evidence, 기기 마스킹 보고가 간다(판독 호출 없음).
        assertThat(ai.privacyRequests()).hasSize(1);
        assertThat(ai.privacyRequests().get(0)).containsEntry("media_id", media.toString()).containsEntry("purpose", "repair_evidence")
                .containsEntry("device_masked", "true");
        assertThat(jdbc.queryForObject("""
                SELECT purpose || ':' || privacy_status || ':' || issue_id || ':' || provider_photo_id FROM media WHERE id = ?""", String.class, media))
                .isEqualTo("REPAIR_EVIDENCE:APPROVED:" + issue + ":m_" + media);
        // 저장된 바이트는 AI가 가린 사본이다(원본 아님).
        byte[] stored = api.get(manager, "/api/v1/media/" + media + "/content").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(stored).isEqualTo(MockAiServer.jpeg(("m_" + media).hashCode() + 1)).isNotEqualTo(MockAiServer.jpeg(77));
        // 같은 키 재시도는 AI를 다시 부르지 않고 같은 결과.
        upload(manager, "REPAIR_EVIDENCE", issue, "true", MockAiServer.jpeg(77), key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaId").value(media.toString()));
        assertThat(ai.privacyRequests()).hasSize(1);
        upload(manager, "REPAIR_EVIDENCE", issue, "true", MockAiServer.jpeg(78), key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 다른 부서 담당자는 볼 수 없다. 보수 완료 증거로 쓸 수 있고 문제 상세에 나온다.
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 공원과");
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", otherDepartment);
        api.get(other, "/api/v1/media/" + media + "/content").andExpect(status().isNotFound());
        repairComplete(2, clock.instant().toString(), "[\"" + media + "\"]", UUID.randomUUID()).andExpect(status().isCreated());
        api.get(manager, "/api/v1/admin/issues/" + issue).andExpect(jsonPath("$.repairEvents[2].evidenceMedia[0].id").value(media.toString()));
        // 보수 증거 사진도 문제 시설의 현재 관할 담당자가 개인정보 부족으로 보류할 수 있고, 보류하면 조회가 막힌다.
        api.post(manager, "/api/v1/admin/media/" + media + "/privacy-reviews", "{\"ok\":false,\"reason\":\"번호판 보임\",\"version\":0}",
                UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.privacyStatus").value("QUARANTINED"));
        api.get(manager, "/api/v1/media/" + media + "/content").andExpect(status().isNotFound());
        api.post(other, "/api/v1/admin/media/" + media + "/privacy-reviews", "{\"ok\":true,\"reason\":\"확인\",\"version\":1}", UUID.randomUUID())
                .andExpect(status().isNotFound());
    }

    @Test
    void stateRoleAndInputAreCheckedBeforeCallingAi() throws Exception {
        upload(manager, "REPAIR_EVIDENCE", issue, "true", MockAiServer.jpeg(1), UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUE_STATE_CONFLICT")).andExpect(jsonPath("$.details.currentStatus").value("OPEN"));
        repairRequest(0, UUID.randomUUID()).andExpect(status().isCreated());
        upload(manager, "REFERENCE", issue, "true", MockAiServer.jpeg(1), UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("purpose"));
        upload(manager, "REPAIR_EVIDENCE", issue, "false", MockAiServer.jpeg(1), UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("deviceMasked"));
        upload(manager, "REPAIR_EVIDENCE", issue, "true", "not an image".getBytes(), UUID.randomUUID()).andExpect(status().isUnsupportedMediaType());
        upload(f.manager, "REPAIR_EVIDENCE", issue, "true", MockAiServer.jpeg(1), UUID.randomUUID()).andExpect(status().isForbidden());
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 공원과");
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", otherDepartment);
        upload(other, "REPAIR_EVIDENCE", issue, "true", MockAiServer.jpeg(1), UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(ai.privacyRequests()).isEmpty();
        assertThat(evidenceRows()).isZero();
    }

    @Test
    void aiFailuresStoreNothing() throws Exception {
        repairRequest(0, UUID.randomUUID()).andExpect(status().isCreated());
        ai.nextPrivacyMask(Reply.error(503, "UNAVAILABLE"), Reply.error(401, "UNAUTHORIZED"),
                new Reply(200, "get_privacy_mask_reference", null, false, 0, "error"));
        // retryAfterSeconds는 고정값이 아니라 서버 설정(inspection.privacy-mask.retry-after, 테스트 7초)이 정한 재시도 안내 값이다(CONTRACT-01).
        assertMatches("ErrorResponse", upload(manager, "REPAIR_EVIDENCE", issue, "true", MockAiServer.jpeg(1), UUID.randomUUID())
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PRIVACY_SERVICE_NOT_READY"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").value(7)).andReturn().getResponse().getContentAsString());
        upload(manager, "REPAIR_EVIDENCE", issue, "true", MockAiServer.jpeg(1), UUID.randomUUID()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRIVACY_SERVICE_NOT_READY")).andExpect(jsonPath("$.details.retryAfterSeconds").value(7));
        upload(manager, "REPAIR_EVIDENCE", issue, "true", MockAiServer.jpeg(1), UUID.randomUUID()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IMAGE_PROCESSING_FAILED"));
        assertThat(evidenceRows()).isZero();
        assertThat(storedRepairFiles()).isZero();
    }

    @Test
    void storedCopyIsRemovedWhenIssueChangesDuringAiCall() throws Exception {
        repairRequest(0, UUID.randomUUID()).andExpect(status().isCreated());
        CountDownLatch gate = new CountDownLatch(1);
        ai.holdPrivacyMask(gate);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> pending = pool.submit(() -> upload(manager, "REPAIR_EVIDENCE", issue, "true", MockAiServer.jpeg(5), UUID.randomUUID())
                    .andReturn().getResponse().getStatus());
            // AI가 요청을 받은 것을 확인한 뒤(시간 가정 없음) 상태를 바꾸고 응답을 보내게 한다.
            for (int i = 0; i < 400 && ai.privacyRequests().isEmpty(); i++) {
                Thread.sleep(10);
            }
            assertThat(ai.privacyRequests()).hasSize(1);
            // AI 검사 중 다른 담당자가 보수를 닫았다(여기서는 상태만 바꿈). 반영 트랜잭션이 상태를 다시 보고 거절해야 한다.
            jdbc.update("UPDATE issues SET status = 'CLOSED', closed_at = now(), version = version + 1 WHERE id = ?", issue);
            gate.countDown();
            assertThat(pending.get()).isEqualTo(409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(evidenceRows()).isZero();
        assertThat(storedRepairFiles()).as("저장한 검사 완료본을 지운다").isZero();
    }

    @org.springframework.beans.factory.annotation.Autowired
    kr.iroum.inspection.analysis.event.AiEventSync events;

    @Test
    void reviewRemaskedRepairEvidenceMustRemainUsableForCompletion() throws Exception {
        // MEDIA-UP-02: 재가림 새 사본도 같은 문제의 증거로 이어져야 한다(media.issue_id). 이전 사본은 대체되어 쓸 수 없다.
        UUID old = uploadedEvidence();
        UUID fresh = remask(old);
        api.get(manager, "/api/v1/media/" + fresh + "/content").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT issue_id FROM media WHERE id = ?", UUID.class, fresh)).isEqualTo(issue);
        repairComplete(2, clock.instant().toString(), "[\"" + old + "\"]", UUID.randomUUID()).andExpect(status().isBadRequest());
        repairComplete(2, clock.instant().toString(), "[\"" + fresh + "\"]", UUID.randomUUID()).andExpect(status().isCreated());
        api.get(manager, "/api/v1/admin/issues/" + issue).andExpect(jsonPath("$.repairEvents[2].evidenceMedia[0].id").value(fresh.toString()))
                .andExpect(jsonPath("$.repairEvents[2].evidenceMedia[0].mediaVersion").value(2));
    }

    @Test
    void remaskAfterCompletionMovesRepairEvidenceToCurrentCopy() throws Exception {
        // 보수 완료 뒤 증거가 재가림되면 보수 이력은 지금 사본을 가리키고(이전 사본은 대체되어 조회 404), 다른 부서는 여전히 볼 수 없다.
        UUID old = uploadedEvidence();
        repairComplete(2, clock.instant().toString(), "[\"" + old + "\"]", UUID.randomUUID()).andExpect(status().isCreated());
        UUID fresh = remask(old);
        api.get(manager, "/api/v1/admin/issues/" + issue).andExpect(jsonPath("$.repairEvents[2].evidenceMedia.length()").value(1))
                .andExpect(jsonPath("$.repairEvents[2].evidenceMedia[0].id").value(fresh.toString()));
        api.get(manager, "/api/v1/media/" + old + "/content").andExpect(status().isNotFound());
        api.get(manager, "/api/v1/media/" + fresh + "/content").andExpect(status().isOk());
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 공원과");
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", otherDepartment);
        api.get(other, "/api/v1/media/" + fresh + "/content").andExpect(status().isNotFound());
        // 보류(관리자 개인정보 판정)도 지금 사본에 적용되고 조회가 막힌다.
        api.post(manager, "/api/v1/admin/media/" + fresh + "/privacy-reviews", "{\"ok\":false,\"reason\":\"번호판 보임\",\"version\":0}",
                UUID.randomUUID()).andExpect(status().isOk());
        api.get(manager, "/api/v1/media/" + fresh + "/content").andExpect(status().isNotFound());
    }

    @Test
    void concurrentRemaskHoldAndCompletionStayConsistent() throws Exception {
        // 시설→문제→사진 순으로 잠그는 보수 완료와, 사진만 잠그는 재가림·보류가 동시에 실행돼도 교착 없이 끝나고 결과가 일관된다:
        // 완료가 이전 사본으로 성공했으면 보수 이력은 재가림 뒤 지금 사본을 가리키고, 실패했으면(400) 문제는 IN_REPAIR 그대로다.
        for (int round = 0; round < 3; round++) {
            UUID old = uploadedEvidence();
            String photo = jdbc.queryForObject("SELECT provider_photo_id FROM media WHERE id = ?", String.class, old);
            ai.remask(photo);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> complete = pool.submit(() -> {
                    start.await();
                    return repairComplete(issueVersion(), clock.instant().toString(), "[\"" + old + "\"]", UUID.randomUUID()).andReturn().getResponse()
                            .getStatus();
                });
                Future<?> sync = pool.submit(() -> {
                    start.await();
                    return events.sync();
                });
                start.countDown();
                int status = complete.get(30, TimeUnit.SECONDS);
                sync.get(30, TimeUnit.SECONDS);
                UUID fresh = jdbc.queryForObject("SELECT id FROM media WHERE provider_photo_id = ? AND superseded_by_media_id IS NULL", UUID.class, photo);
                assertThat(fresh).isNotEqualTo(old);
                assertThat(jdbc.queryForObject("SELECT issue_id FROM media WHERE id = ?", UUID.class, fresh)).isEqualTo(issue);
                if (status == 201) {
                    assertThat(jdbc.queryForList("SELECT media_id FROM repair_event_media WHERE media_id IN (?, ?)", UUID.class, old, fresh))
                            .containsExactly(fresh);
                    return;
                }
                assertThat(status).isEqualTo(400);
                assertThat(issueStatus()).isEqualTo("IN_REPAIR");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void v21BackfillsLegacyRemaskedEvidence() throws Exception {
        // V21: 수정 전 방식으로 만든 재가림 사본(issue_id 없음)과 대체된 사본을 가리키는 보수 이력을 보정하는 SQL을 같은 데이터에 실행해 확인한다.
        UUID old = uploadedEvidence();
        repairComplete(2, clock.instant().toString(), "[\"" + old + "\"]", UUID.randomUUID()).andExpect(status().isCreated());
        UUID legacy = jdbc.queryForObject("""
                INSERT INTO media (program_id, uploaded_by, bucket, object_key, purpose, privacy_status, source_media_id, content_type, size_bytes, sha256,
                    provider_photo_id, provider_image_revision)
                SELECT program_id, uploaded_by, bucket, object_key || '-r2', purpose, privacy_status, id, content_type, size_bytes, sha256,
                    provider_photo_id, 2 FROM media WHERE id = ? RETURNING id""", UUID.class, old);
        jdbc.update("UPDATE media SET superseded_by_media_id = ? WHERE id = ?", legacy, old);
        assertThat(jdbc.queryForObject("SELECT issue_id FROM media WHERE id = ?", UUID.class, legacy)).isNull();
        String sql = new String(getClass().getResourceAsStream("/db/migration/V21__remasked_repair_evidence_issue.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
            if (!statement.isBlank()) {
                jdbc.update(statement);
            }
        }
        assertThat(jdbc.queryForObject("SELECT issue_id FROM media WHERE id = ?", UUID.class, legacy)).isEqualTo(issue);
        assertThat(jdbc.queryForList("SELECT media_id FROM repair_event_media WHERE media_id IN (?, ?)", UUID.class, old, legacy))
                .containsExactly(legacy);
    }

    /** IN_REPAIR 문제에 보수 증거 사진을 올린다(처음 한 번만 요청·착수). */
    private UUID uploadedEvidence() throws Exception {
        jdbc.update("DELETE FROM ai_event_inbox");
        jdbc.update("DELETE FROM ai_event_cursors");
        if ("OPEN".equals(issueStatus())) {
            repairRequest(0, UUID.randomUUID()).andExpect(status().isCreated());
            repairStart(1, UUID.randomUUID()).andExpect(status().isCreated());
        }
        String body = upload(manager, "REPAIR_EVIDENCE", issue, "true", MockAiServer.jpeg(77 + evidenceRows()), UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.mediaId"));
    }

    /** AI 재가림 이벤트를 반영하고 새 사본 ID를 돌려준다. */
    private UUID remask(UUID media) {
        String photo = jdbc.queryForObject("SELECT provider_photo_id FROM media WHERE id = ?", String.class, media);
        ai.remask(photo);
        events.sync();
        UUID fresh = jdbc.queryForObject("SELECT id FROM media WHERE provider_photo_id = ? AND superseded_by_media_id IS NULL", UUID.class, photo);
        assertThat(fresh).isNotEqualTo(media);
        return fresh;
    }

    private ResultActions upload(UUID user, String purpose, UUID issueId, String deviceMasked, byte[] file, UUID key) throws Exception {
        MockMultipartHttpServletRequestBuilder b = MockMvcRequestBuilders.multipart("/api/v1/admin/media");
        b.file(new MockMultipartFile("file", "evidence.jpg", "image/jpeg", file));
        b.param("purpose", purpose).param("issueId", issueId.toString()).param("deviceMasked", deviceMasked).param("deviceMaskReport", REPORT)
                .param("maskingVersion", "web-mask-0.1.0");
        b.cookie(csrf.cookie()).header("X-CSRF-Token", csrf.token()).header("Authorization", f.token(user)).header("Idempotency-Key", key.toString());
        return mvc.perform(b);
    }

    private int evidenceRows() {
        return jdbc.queryForObject("SELECT count(*) FROM media WHERE issue_id = ?", Integer.class, issue);
    }

    private long storedRepairFiles() throws Exception {
        Path dir = paths.media().resolve("inspection-media").resolve(f.program.toString()).resolve("repair");
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
