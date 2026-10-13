package kr.iroum.inspection.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.iroum.inspection.media.MediaStorage;
import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.PatrolFixture;

/** 권한 검사되는 사진 스트리밍(GET /media/{id}/content, PHOTO-07·12). 시설 담당자·활동 담당자(D-40)·시니어 참고 사진만, 사업 총괄·시스템 관리자는 403. */
class MediaContentIntegrationTest extends PhotoTestBase {

    @Autowired
    MediaStorage storage;

    UUID facilityManager;
    UUID inspectionMedia;
    UUID referenceMedia;
    byte[] referenceBytes = MockAiServer.jpeg(42);

    @BeforeEach
    void media() throws Exception {
        facilityManager = f.user("정○○");
        f.role(facilityManager, "FACILITY_MANAGER", f.department);
        UUID submission = UUID.randomUUID();
        submit(0, 0, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk());
        inspectionMedia = jdbc.queryForObject("SELECT approved_media_id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        String key = f.program + "/reference/" + UUID.randomUUID() + ".jpg";
        storage.put(MediaStorage.BUCKET, key, referenceBytes);
        referenceMedia = jdbc.queryForObject("""
                INSERT INTO media (program_id, bucket, object_key, purpose, privacy_status, content_type, size_bytes, sha256)
                VALUES (?, ?, ?, 'REFERENCE', 'APPROVED', 'image/jpeg', ?, sha256(?)) RETURNING id""", UUID.class, f.program, MediaStorage.BUCKET,
                key, referenceBytes.length, referenceBytes);
        jdbc.update("UPDATE capture_slots SET reference_media_id = ? WHERE id = ?", referenceMedia, p.slots().get(3));
    }

    @Test
    void facilityManagerOfCurrentJurisdictionStreamsInspectionPhotoWithoutCaching() throws Exception {
        String key = jdbc.queryForObject("SELECT object_key FROM media WHERE id = ?", String.class, inspectionMedia);
        byte[] body = api.get(facilityManager, content(inspectionMedia)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg")).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(storage.read(MediaStorage.BUCKET, key));
        // 참고 사진도 그 구도 장소의 관할이면 본다.
        api.get(facilityManager, content(referenceMedia)).andExpect(status().isOk());
        // 관할이 옮겨지면 다음 요청부터 막힌다. 다른 부서 담당자는 처음부터 404.
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 하수과");
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", otherDepartment);
        api.get(other, content(inspectionMedia)).andExpect(status().isNotFound());
        jdbc.update("UPDATE facilities SET managing_department_id = ? WHERE id = ?", otherDepartment, f.facilities.get(0));
        api.get(facilityManager, content(inspectionMedia)).andExpect(status().isNotFound());
        api.get(other, content(inspectionMedia)).andExpect(status().isOk());
    }

    @Test
    void seniorSeesOnlyReferencePhotosOfOwnPatrol() throws Exception {
        String path = api.get(p.users().get(1), "/api/v1/patrols/" + p.id() + "/sync-state").andExpect(status().isOk())
                .andExpect(jsonPath("$.assets[0].mediaId").value(referenceMedia.toString())).andReturn().getResponse().getContentAsString();
        assertThat(path).contains("/api/v1/media/" + referenceMedia + "/content");
        byte[] body = api.get(p.users().get(1), content(referenceMedia)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(referenceBytes);
        // 점검 사진(본인이 찍었어도)과 다른 순회의 참고 사진은 근거가 없어 404.
        api.get(p.users().get(0), content(inspectionMedia)).andExpect(status().isNotFound());
        PatrolFixture.Patrol other = f.patrol(2);
        api.get(other.users().get(0), content(referenceMedia)).andExpect(status().isNotFound());
    }

    @Test
    void undecidedRolesAndNonPublicCopiesAreNotServed() throws Exception {
        // 사업 총괄·시스템 관리자는 조회 범위를 넓히지 않았다(403).
        for (String role : new String[] {"PROGRAM_MANAGER", "SYSTEM_ADMIN"}) {
            UUID u = f.user("관리" + role.charAt(0));
            f.role(u, role, f.agency);
            api.get(u, content(referenceMedia)).andExpect(status().isForbidden());
        }
        // 보류(QUARANTINED)·대체된 사본은 담당자에게도 주지 않는다.
        jdbc.update("UPDATE media SET privacy_status = 'QUARANTINED' WHERE id = ?", referenceMedia);
        api.get(facilityManager, content(referenceMedia)).andExpect(status().isNotFound());
        api.get(p.users().get(0), content(referenceMedia)).andExpect(status().isNotFound());
        String photo = jdbc.queryForObject("SELECT provider_photo_id FROM media WHERE id = ?", String.class, inspectionMedia);
        jdbc.update("DELETE FROM ai_event_inbox WHERE provider = 'ai-v2'");
        jdbc.update("DELETE FROM ai_event_cursors WHERE provider = 'ai-v2'");
        ai.remask(photo);
        events.sync();
        api.get(facilityManager, content(inspectionMedia)).andExpect(status().isNotFound());
        UUID fresh = jdbc.queryForObject("SELECT superseded_by_media_id FROM media WHERE id = ?", UUID.class, inspectionMedia);
        api.get(facilityManager, content(fresh)).andExpect(status().isOk());
        api.get(facilityManager, content(UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void activityManagerSeesApprovedPhotosOfManagedPatrolsButCannotDecidePrivacy() throws Exception {
        // 확정 정책(2026-10-13): 활동 담당자는 담당 수행기관·거점 순회의 승인된 참고 사진과 점검 사진을 조회한다.
        api.get(f.manager, content(inspectionMedia)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        api.get(f.manager, content(referenceMedia)).andExpect(status().isOk());
        // 거점만 담당해도 그 거점 순회는 본다. 다른 기관 담당자는 404.
        UUID baseManager = f.user("거점○○");
        f.role(baseManager, "ACTIVITY_MANAGER", f.base);
        api.get(baseManager, content(inspectionMedia)).andExpect(status().isOk());
        UUID otherAgency = f.org("AGENCY", null, "다른 복지관");
        UUID outsider = f.user("타기관○○");
        f.role(outsider, "ACTIVITY_MANAGER", otherAgency);
        api.get(outsider, content(inspectionMedia)).andExpect(status().isNotFound());
        // 사진 판정·개인정보 검토는 시설 담당자에게만 있다.
        api.post(f.manager, "/api/v1/admin/media/" + inspectionMedia + "/privacy-reviews", "{\"ok\":false,\"reason\":\"확인\",\"version\":0}",
                UUID.randomUUID()).andExpect(status().isForbidden());
        // 승인되지 않은 사진은 보지 못한다.
        jdbc.update("UPDATE media SET privacy_status = 'QUARANTINED' WHERE id = ?", inspectionMedia);
        api.get(f.manager, content(inspectionMedia)).andExpect(status().isNotFound());
    }

    @Autowired
    kr.iroum.inspection.analysis.event.AiEventSync events;

    private static String content(UUID media) {
        return "/api/v1/media/" + media + "/content";
    }
}
