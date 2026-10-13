package kr.iroum.inspection.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import kr.iroum.inspection.analysis.client.AiClient;
import kr.iroum.inspection.analysis.client.AiClient.AiException;
import kr.iroum.inspection.inspection.DeviceMaskReport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 AI 판독 서버와의 HTTP 연결 검증(선택 실행). ITDA_REAL_AI_URL(예: http://localhost:18000)과 ITDA_REAL_AI_KEY가 있을 때만 돈다.
 * mock 서버 검증과 구분해 보고한다. 가상으로 그린 사진만 보낸다(실제 사진·개인정보 없음).
 */
@EnabledIfEnvironmentVariable(named = "ITDA_REAL_AI_URL", matches = ".+")
class RealAiConnectionTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final String key = System.getenv().getOrDefault("ITDA_REAL_AI_KEY", "");
    private final AiClient client = new AiClient(System.getenv("ITDA_REAL_AI_URL"), key, Duration.ofSeconds(5), Duration.ofSeconds(60), json);

    @Test
    void contractOverRealHttp() throws Exception {
        JsonNode health = client.health();
        System.out.println("[real-ai] health=" + health);
        assertThat(health.get("ok").isBoolean()).isTrue();
        assertThat(health.path("contract_version").asString()).isEqualTo("2.0");

        String photoId = "insp_" + UUID.randomUUID().toString().replace("-", "");
        DeviceMaskReport report = DeviceMaskReport.parse(
                "{\"faces\":0,\"plates\":0,\"vehicles\":0,\"detector\":\"synthetic-test\",\"appVersion\":\"0.0.0-test\",\"boxes\":[]}", json);
        byte[] image = syntheticPhoto();
        JsonNode result = client.analyze(new AiClient.AnalyzeCall(photoId, image, "image/jpeg", "test-participant",
                Instant.parse("2026-10-15T01:20:00Z"), report.toAi(true), "roadside"));
        String status = AnalyzeOutcome.status(result);
        System.out.println("[real-ai] analyze status=" + status + " stored=" + AnalyzeOutcome.hasStoredImage(result) + " retry="
                + result.get("retry") + " meta.privacy=" + result.path("meta").path("privacy"));
        assertThat(List.of("ok", "needs_review", "retake", "error")).contains(status);
        assertThat(result.path("photo_id").asString()).isEqualTo(photoId);
        if (!AnalyzeOutcome.hasStoredImage(result)) {
            assertThat(client.result(photoId)).as("저장 없는 결과는 /results에 없다").isEmpty();
            return;
        }
        // 저장된 결과: 검사본 다운로드, 결과 조회, 같은 ID 재현, 보류(410)·해제, 이벤트.
        AiClient.MaskedImage masked = client.maskedImage(photoId);
        System.out.println("[real-ai] masked revision=" + masked.revision() + " bytes=" + masked.bytes().length + " type=" + masked.contentType());
        assertThat(masked.revision()).isGreaterThanOrEqualTo(1);
        assertThat(masked.bytes()).isNotEqualTo(image);
        assertThat(client.result(photoId)).isPresent();
        JsonNode replay = client.analyze(new AiClient.AnalyzeCall(photoId, image, "image/jpeg", "test-participant",
                Instant.parse("2026-10-15T01:20:00Z"), report.toAi(true), "roadside"));
        assertThat(replay.path("meta").path("replayed").asBoolean()).isTrue();
        long before = client.events(0, 1).path("last_seq").asLong();
        String first = "itda-privacy-review:" + UUID.randomUUID();
        String second = "itda-privacy-review:" + UUID.randomUUID();
        String release = "itda-privacy-review:" + UUID.randomUUID();
        assertThat(client.auditMasking(photoId, false, first).path("hidden").asBoolean()).isTrue();
        assertThatThrownBy(() -> client.maskedImage(photoId)).isInstanceOfSatisfying(AiException.class,
                e -> assertThat(e.status() + ":" + e.code()).isEqualTo("410:PHOTO_HIDDEN"));
        assertThat(client.auditMasking(photoId, false, second).path("hidden").asBoolean()).isTrue(); // 이미 보류: 알림 없음
        assertThat(client.auditMasking(photoId, true, release).path("hidden").asBoolean()).isFalse();
        assertThat(client.auditMasking(photoId, true, null).path("hidden").asBoolean()).isFalse(); // 이미 공개: 알림 없음
        JsonNode events = client.events(before, 100);
        System.out.println("[real-ai] events=" + events);
        // 백엔드 메아리 판정(PHOTO-15)이 기대는 AI 동작: 상태가 바뀔 때만 알림, reason = 요청 note.
        java.util.List<String> got = new java.util.ArrayList<>();
        events.path("events").forEach(e -> {
            if (photoId.equals(e.path("photo_id").asString())) {
                got.add(e.path("type").asString() + "|" + e.path("reason").asString(null));
            }
        });
        assertThat(got).containsExactly("image_hidden|" + first, "image_unhidden|" + release);
        if (!key.isBlank()) {
            AiClient wrong = new AiClient(System.getenv("ITDA_REAL_AI_URL"), "wrong-key", Duration.ofSeconds(5), Duration.ofSeconds(30), json);
            assertThatThrownBy(() -> wrong.events(0, 1)).isInstanceOfSatisfying(AiException.class,
                    e -> assertThat(e.kind() + ":" + e.status() + ":" + e.code()).isEqualTo("REJECTED:401:UNAUTHORIZED"));
        }
    }

    /** 밝고 선명한 가상 장면(인도 블록 무늬). 사람·차량 없음. */
    private static byte[] syntheticPhoto() throws Exception {
        BufferedImage img = new BufferedImage(1024, 768, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Random r = new Random(7);
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
