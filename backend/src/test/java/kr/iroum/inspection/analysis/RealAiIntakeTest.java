package kr.iroum.inspection.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.analysis.client.AiClient;
import kr.iroum.inspection.analysis.client.AiClient.AiException;
import kr.iroum.inspection.analysis.event.AiEventSync;
import kr.iroum.inspection.auth.AuthTestSupport;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.media.MediaStorage;
import kr.iroum.inspection.media.PrivacyReleaseSender;
import kr.iroum.inspection.support.ApiCaller;
import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.PatrolFixture;

/**
 * 업무 백엔드 ↔ 실제 AI 판독 서버 연결 검증(선택 실행, ITDA_REAL_AI_URL·ITDA_REAL_AI_KEY가 있을 때만). 실제 DB(Testcontainers)와 실제 AI HTTP를 쓴다.
 * 판독 모델이 실제(Ollama)인지 mock인지는 AI /health의 provider로 기록한다. 가상으로 그린 사진만 보낸다(사람·차량·개인정보 없음).
 * 경로: a 사진 제출→판독→업무 반영, b 검사 완료 사본 저장→권한별 조회, c 개인정보 보류→조회 차단→재승인→조회 복구, e AI 이벤트→업무 반영.
 * d(보류 중 재가림)는 AI가 사진에서 가림 누락을 찾을 때만 생겨 개인정보 없는 자료로는 일으킬 수 없다(mock 회귀 테스트로만 검증).
 * 판독 결과가 저장본 없는 retake·error면 b 이후는 건너뛴다(assumption).
 */
@EnabledIfEnvironmentVariable(named = "ITDA_REAL_AI_URL", matches = ".+")
@SpringBootTest(properties = "inspection.jobs.scheduling-enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class RealAiIntakeTest {

    @DynamicPropertySource
    static void realAi(DynamicPropertyRegistry registry) throws Exception {
        registry.add("inspection.ai.base-url", () -> System.getenv("ITDA_REAL_AI_URL"));
        registry.add("inspection.ai.api-key", () -> System.getenv().getOrDefault("ITDA_REAL_AI_KEY", ""));
        registry.add("inspection.ai.request-timeout", () -> "60s");
        String media = Files.createTempDirectory("itda-real-media").toString();
        registry.add("inspection.media.storage-root", () -> media);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Autowired
    PhoneCrypto phones;

    @Autowired
    MediaStorage storage;

    @Autowired
    AiClient ai;

    @Autowired
    AiEventSync events;

    @Autowired
    PrivacyReleaseSender sender;

    @Test
    void businessPathsThroughRealAi() throws Exception {
        System.out.println("[real-ai] health=" + ai.health());
        PatrolFixture f = new PatrolFixture(jdbc, sessions, phones);
        PatrolFixture.Patrol p = f.patrol(2);
        ApiCaller api = new ApiCaller(mvc, f);
        UUID facilityManager = f.user("정○○");
        f.role(facilityManager, "FACILITY_MANAGER", f.department);
        UUID programManager = f.user("총괄○○");
        f.role(programManager, "PROGRAM_MANAGER", f.agency);
        // 이 실행 이전의 AI 이벤트는 건너뛴다(이번 사진의 이벤트만 반영 대상).
        long before = ai.events(0, 1).path("last_seq").asLong();
        jdbc.update("INSERT INTO ai_event_cursors (provider, generation, last_applied_seq) VALUES (?, 'g1', ?) ON CONFLICT (provider, generation) DO UPDATE SET last_applied_seq = EXCLUDED.last_applied_seq",
                AiEventSync.PROVIDER, before);

        // a. 사진 제출 → 실제 AI 판독 → 업무 반영
        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        var request = MockMvcRequestBuilders.multipart("/api/v1/patrols/{p}/stops/{s}/inspections", p.id(), p.stops().get(0));
        request.file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", photo()));
        request.param("clientSubmissionId", UUID.randomUUID().toString()).param("captureSlotId", p.slots().get(0).toString())
                .param("attemptId", UUID.randomUUID().toString()).param("capturedAt", "2026-10-15T01:20:00Z")
                .param("maskingVersion", "synthetic-test").param("deviceMasked", "true")
                .param("deviceMaskReport", "{\"faces\":0,\"plates\":0,\"vehicles\":0,\"detector\":\"synthetic-test\",\"appVersion\":\"0.0.0-test\"}");
        request.cookie(csrf.cookie()).header("X-CSRF-Token", csrf.token()).header("Authorization", f.token(p.users().get(0)));
        long started = System.nanoTime();
        MvcResult result = mvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        System.out.println("[real-ai] a submit HTTP " + result.getResponse().getStatus() + " in " + (System.nanoTime() - started) / 1_000_000
                + "ms " + body);
        assertThat(result.getResponse().getStatus()).isIn(200, 202, 422, 503);
        assumeTrue(result.getResponse().getStatus() != 422 && result.getResponse().getStatus() != 503, "a: 저장본 없음/AI 장애 → b 이후 미실행");
        UUID inspection = UUID.fromString(JsonPath.read(body, "$.inspectionId"));
        String snapshot = jdbc.queryForObject("""
                SELECT r.result_json->>'status' || ' issues=' || jsonb_array_length(COALESCE(r.result_json->'issues', '[]'::jsonb)) || ' model='
                    || COALESCE(r.model_version, '-') FROM analysis_results r JOIN analysis_jobs j ON j.id = r.analysis_job_id WHERE j.inspection_id = ?""",
                String.class, inspection);
        System.out.println("[real-ai] a snapshot " + snapshot + " capture=" + JsonPath.read(body, "$.captureStatus"));
        assumeTrue("RECEIVED".equals(JsonPath.read(body, "$.receiptStatus")), "a: 저장본 없는 결과 → b 이후 미실행");

        // b. 서버 검사 완료 사본 저장 → 권한별 조회
        UUID media = jdbc.queryForObject("SELECT media_id FROM inspections WHERE id = ?", UUID.class, inspection);
        String photoId = jdbc.queryForObject("SELECT provider_photo_id FROM media WHERE id = ?", String.class, media);
        String key = jdbc.queryForObject("SELECT object_key FROM media WHERE id = ?", String.class, media);
        byte[] stored = storage.read(MediaStorage.BUCKET, key);
        assertThat(stored).isEqualTo(ai.maskedImage(photoId).bytes());
        String content = "/api/v1/media/" + media + "/content";
        assertThat(api.get(facilityManager, content).andReturn().getResponse().getContentAsByteArray()).isEqualTo(stored);
        assertThat(api.get(f.manager, content).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(api.get(p.users().get(0), content).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(api.get(programManager, content).andReturn().getResponse().getStatus()).isEqualTo(403);
        System.out.println("[real-ai] b stored copy = AI masked image, FM 200 / AM 200 / senior 404 / PM 403");

        // c. 담당자 보류 → AI 보류·업무 조회 차단 → 재승인 → AI 해제·업무 공개
        long version = jdbc.queryForObject("SELECT version FROM media WHERE id = ?", Long.class, media);
        String held = api.post(facilityManager, "/api/v1/admin/media/" + media + "/privacy-reviews",
                "{\"ok\":false,\"reason\":\"가림 재확인\",\"version\":" + version + "}", UUID.randomUUID()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(held, "$.privacyStatus")).isEqualTo("QUARANTINED");
        assertThat(api.get(facilityManager, content).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThatThrownBy(() -> ai.maskedImage(photoId)).isInstanceOfSatisfying(AiException.class, e -> assertThat(e.status()).isEqualTo(410));
        String released = api.post(facilityManager, "/api/v1/admin/media/" + media + "/privacy-reviews",
                "{\"ok\":true,\"reason\":\"가림 확인\",\"version\":" + (version + 1) + "}", UUID.randomUUID()).andReturn().getResponse()
                .getContentAsString();
        System.out.println("[real-ai] c release decision " + released);
        sender.drain();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("APPROVED");
        assertThat(ai.maskedImage(photoId).bytes()).isNotEmpty();
        assertThat(api.get(facilityManager, content).andReturn().getResponse().getStatus()).isEqualTo(200);
        System.out.println("[real-ai] c hold → 404/AI 410 → release COMPLETED → 200/AI 200");

        // e. AI 이벤트 → 업무 반영. 우리 결정으로 생긴 보류·해제 알림을 받아도 최신 결정(공개)과 어긋나지 않아야 한다.
        AiEventSync.SyncReport report = events.sync();
        System.out.println("[real-ai] e sync " + report + " events=" + ai.events(before, 100));
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).as("e: 공개 상태 유지")
                .isEqualTo("APPROVED");
        assertThat(ai.maskedImage(photoId).bytes()).isNotEmpty();
        // AI 쪽에서 생긴 보류(다른 경로의 가림 점검)는 업무에 반영돼 즉시 차단된다.
        ai.auditMasking(photoId, false);
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).isEqualTo("QUARANTINED");
        ai.auditMasking(photoId, true);
        events.sync();
        assertThat(jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media)).as("해제 알림만으로 공개하지 않음")
                .isEqualTo("QUARANTINED");
        System.out.println("[real-ai] e AI-side hold → QUARANTINED, unhidden event alone → still QUARANTINED, cursor="
                + jdbc.queryForObject("SELECT last_applied_seq FROM ai_event_cursors WHERE provider = ?", Long.class, AiEventSync.PROVIDER));
    }

    private static byte[] photo() throws Exception {
        BufferedImage img = new BufferedImage(1024, 768, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        java.util.Random r = new java.util.Random(11);
        g.setColor(new Color(150, 170, 190));
        g.fillRect(0, 0, 1024, 300);
        for (int x = 0; x < 1024; x += 64) {
            for (int y = 300; y < 768; y += 32) {
                g.setColor(new Color(160 + r.nextInt(60), 120 + r.nextInt(40), 90 + r.nextInt(30)));
                g.fillRect(x + ((y / 32) % 2) * 32, y, 60, 28);
            }
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
