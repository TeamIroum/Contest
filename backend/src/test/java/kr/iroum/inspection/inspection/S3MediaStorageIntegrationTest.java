package kr.iroum.inspection.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.TestPropertySource;

import kr.iroum.inspection.analysis.event.AiEventSync;
import kr.iroum.inspection.media.MediaStorage;
import kr.iroum.inspection.media.S3MediaStorage;
import kr.iroum.inspection.support.LocalS3;
import kr.iroum.inspection.support.MockAiServer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * inspection.media.store=s3로 사진 접수·조회·보류·재가림과 저장 실패·정리 경로를 다시 확인한다.
 * S3 호환 컨테이너(LocalStack)와 계약 기반 mock AI를 쓴다. 실제 AWS S3·IAM·버킷 정책·실제 AI 검증이 아니다.
 */
@TestPropertySource(properties = "inspection.media.store=s3")
@Import(S3MediaStorageIntegrationTest.LocalS3Settings.class)
class S3MediaStorageIntegrationTest extends PhotoTestBase {

    static final String BUCKET = "itda-it-media";
    static final String PREFIX = "it";

    /**
     * 테스트 전용 S3 설정. 운영 코드는 기본 자격 증명 체인만 쓰므로, 컨테이너 시험용 자격 증명을 그 체인의 첫 단계(JVM 시스템 속성)로 준다.
     * 운영 설정 파일에는 접근 키를 두지 않는다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class LocalS3Settings {
        @Bean
        DynamicPropertyRegistrar localS3Properties() {
            var c = LocalS3.container();
            try (S3Client s3 = LocalS3.client()) {
                if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
                    s3.createBucket(b -> b.bucket(BUCKET));
                }
            }
            System.setProperty("aws.accessKeyId", c.getAccessKey());
            System.setProperty("aws.secretAccessKey", c.getSecretKey());
            return registry -> {
                registry.add("inspection.media.s3.bucket", () -> BUCKET);
                registry.add("inspection.media.s3.prefix", () -> PREFIX);
                registry.add("inspection.media.s3.region", c::getRegion);
                registry.add("inspection.media.s3.endpoint", () -> c.getEndpoint().toString());
            };
        }
    }

    @Autowired
    MediaStorage storage;

    @Autowired
    AiEventSync events;

    S3Client s3;
    UUID facilityManager;

    @BeforeEach
    void s3() {
        s3 = LocalS3.client();
        facilityManager = f.user("정○○");
        f.role(facilityManager, "FACILITY_MANAGER", f.department);
    }

    @org.junit.jupiter.api.AfterEach
    void closeClient() {
        s3.close();
    }

    @Test
    void acceptedPhotoIsStoredInS3AndServedOnlyThroughPermissionCheckedEndpoint() throws Exception {
        assertThat(storage).isInstanceOf(S3MediaStorage.class);
        UUID submission = UUID.randomUUID();
        submit(0, 0, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.captureStatus").value("ACCEPTED"));
        UUID media = jdbc.queryForObject("SELECT approved_media_id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        String bucket = jdbc.queryForObject("SELECT bucket FROM media WHERE id = ?", String.class, media);
        String key = jdbc.queryForObject("SELECT object_key FROM media WHERE id = ?", String.class, media);
        // DB에는 논리 이름 그대로, S3에는 접두사/논리 이름/키로 암호화·Content-Type과 함께 저장된다. 파일 시스템에는 쓰지 않는다.
        assertThat(bucket).isEqualTo(MediaStorage.BUCKET);
        String objectKey = PREFIX + "/" + MediaStorage.BUCKET + "/" + key;
        HeadObjectResponse head = s3.headObject(r -> r.bucket(BUCKET).key(objectKey));
        assertThat(head.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(head.contentType()).isEqualTo("image/jpeg");
        byte[] stored = s3.getObjectAsBytes(r -> r.bucket(BUCKET).key(objectKey)).asByteArray();
        assertThat(stored).isNotEqualTo(MockAiServer.jpeg(1));
        try (Stream<Path> files = Files.walk(paths.media())) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }

        // 조회는 업무 API로만: 관할 담당자는 200(no-store), 범위 밖은 404.
        byte[] body = api.get(facilityManager, content(media)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg")).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(stored);
        api.get(p.users().get(0), content(media)).andExpect(status().isNotFound());

        // 개인정보 보류(시설 담당자 판정)는 다음 요청부터 막는다. 객체가 S3에 있어도 404.
        api.post(facilityManager, "/api/v1/admin/media/" + media + "/privacy-reviews", "{\"ok\":false,\"reason\":\"가림 재확인\",\"version\":0}",
                UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.privacyStatus").value("QUARANTINED"));
        api.get(facilityManager, content(media)).andExpect(status().isNotFound());
        api.get(f.manager, content(media)).andExpect(status().isNotFound());
        assertThat(LocalS3.keys(s3, BUCKET)).contains(objectKey);
    }

    @Test
    void remaskedCopyIsStoredAsNewS3ObjectAndOldCopyIsNoLongerServed() throws Exception {
        jdbc.update("DELETE FROM ai_event_inbox WHERE provider = ?", AiEventSync.PROVIDER);
        jdbc.update("DELETE FROM ai_event_cursors WHERE provider = ?", AiEventSync.PROVIDER);
        UUID submission = UUID.randomUUID();
        submit(0, 0, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk());
        UUID media = jdbc.queryForObject("SELECT approved_media_id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        String photo = jdbc.queryForObject("SELECT provider_photo_id FROM media WHERE id = ?", String.class, media);
        ai.remask(photo);
        events.sync();
        UUID fresh = jdbc.queryForObject("SELECT superseded_by_media_id FROM media WHERE id = ?", UUID.class, media);
        String freshKey = jdbc.queryForObject("SELECT object_key FROM media WHERE id = ?", String.class, fresh);
        assertThat(freshKey).contains("/remask/" + photo + "/");
        assertThat(LocalS3.keys(s3, BUCKET)).contains(PREFIX + "/" + MediaStorage.BUCKET + "/" + freshKey);
        api.get(facilityManager, content(media)).andExpect(status().isNotFound());
        api.get(facilityManager, content(fresh)).andExpect(status().isOk());
    }

    @Test
    void missingObjectIsReportedAsUnavailableNotServed() throws Exception {
        UUID submission = UUID.randomUUID();
        submit(0, 0, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk());
        UUID media = jdbc.queryForObject("SELECT approved_media_id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        String key = jdbc.queryForObject("SELECT object_key FROM media WHERE id = ?", String.class, media);
        s3.deleteObject(r -> r.bucket(BUCKET).key(PREFIX + "/" + MediaStorage.BUCKET + "/" + key));
        api.get(facilityManager, content(media)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void storageFailureDuringIntakeLeavesNoMediaAndSameIdResendRecovers() throws Exception {
        // 저장 실패를 실제 S3 오류로 만든다: 버킷을 잠시 지운다(PutObject → 404 NoSuchBucket).
        LocalS3.emptyAndDelete(s3, BUCKET);
        UUID submission = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        try {
            submit(0, 0, submission, attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ANALYSIS_UNAVAILABLE"))
                    .andExpect(jsonPath("$.details.receiptStatus").value("RETRY_REQUIRED"));
        } finally {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
        UUID inspection = jdbc.queryForObject("SELECT id FROM inspections WHERE client_submission_id = ?", UUID.class, submission);
        // 없는 객체를 가리키는 사진 행이 남지 않는다. 사진은 기기에 남아 같은 ID 재전송으로 복구한다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media WHERE program_id = ?", Integer.class, f.program)).isZero();
        assertThat(jdbc.queryForObject("SELECT receipt_status || ':' || (media_id IS NULL) FROM inspections WHERE id = ?", String.class,
                inspection)).isEqualTo("RETRY_REQUIRED:true");
        assertThat(jdbc.queryForObject("SELECT active_inspection_id IS NULL FROM capture_slots WHERE id = ?", Boolean.class, p.slots().get(0)))
                .isTrue();

        submit(0, 0, submission, attempt, "2026-10-15T01:20:00Z", MockAiServer.jpeg(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.inspectionId").value(inspection.toString())).andExpect(jsonPath("$.captureStatus").value("ACCEPTED"));
        String key = jdbc.queryForObject("SELECT m.object_key FROM inspections i JOIN media m ON m.id = i.approved_media_id WHERE i.id = ?",
                String.class, inspection);
        assertThat(LocalS3.keys(s3, BUCKET)).containsExactly(PREFIX + "/" + MediaStorage.BUCKET + "/" + key);
    }

    @Test
    void transactionFailureAfterPutDeletesTheStoredObject() throws Exception {
        // 저장 뒤 결과 반영 트랜잭션이 실패하게 한다(이 사업의 사진 행 삽입을 DB가 거부).
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_reject_media() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test: media insert rejected'; END $$""");
        jdbc.execute("CREATE TRIGGER test_reject_media BEFORE INSERT ON media FOR EACH ROW WHEN (NEW.program_id = '" + f.program
                + "') EXECUTE FUNCTION test_reject_media()");
        UUID submission = UUID.randomUUID();
        try {
            submit(0, 0, submission, UUID.randomUUID(), "2026-10-15T01:20:00Z", MockAiServer.jpeg(1))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_media ON media");
            jdbc.execute("DROP FUNCTION test_reject_media()");
        }
        // AI 검사본을 받아 저장했다(저장이 실패했다면 사진 행 삽입 없이 503 RETRY_REQUIRED였다). 그 뒤 트랜잭션 실패로 500이 됐고
        // deleteQuietly가 객체를 지웠다. 사진 행도 없다.
        assertThat(ai.analyzeCalls()).isEqualTo(1);
        assertThat(ai.imageRequests()).hasSize(1);
        assertThat(LocalS3.keys(s3, BUCKET)).noneMatch(k -> k.startsWith(PREFIX + "/" + MediaStorage.BUCKET + "/" + f.program + "/"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media WHERE program_id = ?", Integer.class, f.program)).isZero();
        // 시도는 접수 리스 상태로 남아 리스 만료 뒤 복구 작업이 다시 판단한다(파일 시스템 저장소와 같은 경로).
        assertThat(jdbc.queryForObject("SELECT receipt_status FROM inspections WHERE client_submission_id = ?", String.class, submission))
                .isEqualTo("RECEIVING");
    }

    private static String content(UUID media) {
        return "/api/v1/media/" + media + "/content";
    }
}
