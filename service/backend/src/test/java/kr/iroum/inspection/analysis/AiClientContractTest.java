package kr.iroum.inspection.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kr.iroum.inspection.analysis.client.AiClient;
import kr.iroum.inspection.analysis.client.AiClient.AiException;
import kr.iroum.inspection.analysis.client.AiClient.Kind;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.inspection.DeviceMaskReport;
import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 클라이언트 어댑터의 계약 기반 mock 검증(실제 AI 서버 연결 아님). AI 계약 2.0 + 2026-10-12 전달 규칙:
 * snake_case 변환(app_version·boxes), lat/lon 미전송, HTTP+code 분기, 410·401·연결 실패·시간 초과 구분, audit ok 불리언, health ok=false.
 */
class AiClientContractTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private MockAiServer ai;
    private AiClient client;

    @BeforeEach
    void setUp() {
        ai = new MockAiServer();
        ai.requireKey("k1");
        client = new AiClient(ai.baseUrl(), "k1", Duration.ofSeconds(2), Duration.ofSeconds(1), json);
    }

    @AfterEach
    void tearDown() {
        ai.close();
    }

    @Test
    void analyzeSendsSnakeCaseReportWithBoxesTagsAndNoCoordinates() {
        DeviceMaskReport report = DeviceMaskReport.parse("""
                {"faces":1,"plates":0,"vehicles":0,"detector":"yunet+plate-t384+effdet0","appVersion":"0.3.0",
                 "boxes":[{"kind":"face","box":[0.41,0.12,0.47,0.21]}]}""", json);
        JsonNode result = client.analyze(new AiClient.AnalyzeCall("insp_abc", MockAiServer.jpeg(1), "image/jpeg", "p-1",
                Instant.parse("2026-10-15T01:30:00Z"), report.toAi(true), "school_route,roadside"));
        assertThat(result.get("status").asString()).isEqualTo("ok");
        MockAiServer.AnalyzeRequest sent = ai.analyzeRequests().get(0);
        assertThat(sent.apiKey()).isEqualTo("k1");
        assertThat(sent.fields()).containsEntry("photo_id", "insp_abc").containsEntry("device_masked", "true")
                .containsEntry("location_tags", "school_route,roadside").containsEntry("captured_at", "2026-10-15T01:30:00Z")
                .doesNotContainKeys("lat", "lon");
        JsonNode dmr = json.readTree(sent.fields().get("device_mask_report"));
        assertThat(dmr.get("app_version").asString()).isEqualTo("0.3.0");
        assertThat(dmr.has("appVersion")).isFalse();
        assertThat(dmr.get("boxes").get(0).get("kind").asString()).isEqualTo("face");
        assertThat(dmr.get("boxes").get(0).get("box").get(3).asDouble()).isEqualTo(0.21);
        assertThat(sent.image()).isEqualTo(MockAiServer.jpeg(1));
        // boxes를 보내지 않은 보고는 boxes 키 없이 전달(AI 계약: 이전과 같은 동작)
        DeviceMaskReport plain = DeviceMaskReport.parse("{\"faces\":0,\"plates\":0,\"vehicles\":0,\"detector\":\"d\",\"appVersion\":\"1\"}", json);
        assertThat(plain.toAi(!plain.boxes().isEmpty())).doesNotContainKey("boxes").containsEntry("app_version", "1");
    }

    @Test
    void errorsAreClassifiedByHttpStatusAndCodeNotByDetail() {
        ai.nextAnalyze(Reply.error(400, "INVALID_PHOTO_ID"), Reply.error(503, "UNAVAILABLE"), Reply.drop(),
                Reply.fixture("analyze_01_normal").delayed(2500), Reply.error(429, "RATE"));
        assertKind(Kind.REJECTED, 400, "INVALID_PHOTO_ID");
        assertKind(Kind.TRANSIENT, 503, "UNAVAILABLE");
        assertKind(Kind.TRANSIENT, 0, null);
        assertKind(Kind.TRANSIENT, 0, null); // 요청 제한 시간 초과
        assertKind(Kind.TRANSIENT, 429, "RATE");
        AiClient wrongKey = new AiClient(ai.baseUrl(), "wrong", Duration.ofSeconds(2), Duration.ofSeconds(1), json);
        assertThatThrownBy(() -> wrongKey.events(0, 10)).isInstanceOfSatisfying(AiException.class,
                e -> assertThat(e.kind() + ":" + e.status() + ":" + e.code()).isEqualTo("REJECTED:401:UNAUTHORIZED"));
        // HTTP 200 + status:error는 예외가 아니라 결과(접수 성공 아님은 호출자가 판단)
        ai.nextAnalyze(Reply.fixture("analyze_10_error_unreadable"));
        assertThat(analyze("insp_err").get("status").asString()).isEqualTo("error");
    }

    @Test
    void imagesResultsAuditEventsAndHealthFollowTheContract() {
        ai.nextAnalyze(Reply.fixture("analyze_07_needs_review_unknown"));
        analyze("insp_img");
        AiClient.MaskedImage img = client.maskedImage("insp_img");
        assertThat(img.revision()).isEqualTo(1);
        assertThat(img.contentType()).isEqualTo("image/jpeg");
        assertThat(client.result("insp_img")).isPresent();
        assertThat(client.result("insp_none")).isEmpty();
        // 같은 photo_id 재전송은 저장 결과 재현(replayed)
        assertThat(analyze("insp_img").get("meta").get("replayed").asBoolean()).isTrue();
        // 보류: 410 PHOTO_HIDDEN, audit는 ok 불리언만 보내고 실명·메모를 보내지 않음
        JsonNode hold = client.auditMasking("insp_img", false);
        assertThat(hold.get("hidden").asBoolean()).isTrue();
        assertThat(ai.auditRequests().get(0).get("body").toString()).isEqualTo("{\"ok\":false}");
        assertThatThrownBy(() -> client.maskedImage("insp_img")).isInstanceOfSatisfying(AiException.class,
                e -> assertThat(e.code()).isEqualTo("PHOTO_HIDDEN"));
        assertThat(client.auditMasking("insp_img", true).get("hidden").asBoolean()).isFalse();
        ai.remask("insp_img");
        assertThat(client.maskedImage("insp_img").revision()).isEqualTo(2);
        JsonNode page = client.events(0, 100);
        // 실제 AI 서버처럼 가림 점검 기록으로 보류 상태가 바뀌면(공개→보류, 보류→공개) 알림을 남긴다.
        java.util.List<String> types = new java.util.ArrayList<>();
        page.get("events").forEach(e -> types.add(e.get("type").asString()));
        assertThat(types).containsExactly("image_hidden", "image_unhidden", "image_remasked");
        ai.healthOk(false);
        assertThat(client.health().get("ok").asBoolean()).isFalse();
    }

    @Test
    void photoIdAndMaskReportBoundaries() {
        assertThat(AiClient.validPhotoId("insp_" + "a".repeat(75))).isTrue();
        assertThat(AiClient.validPhotoId("a".repeat(81))).isFalse();
        assertThat(AiClient.validPhotoId("m_ref")).isFalse();
        assertThat(AiClient.validPhotoId("M_ref")).isTrue();
        assertThat(AiClient.validPhotoId("insp.1")).isFalse();
        for (String bad : new String[] {"[]", "{\"faces\":-1,\"plates\":0,\"vehicles\":0,\"detector\":\"d\",\"appVersion\":\"1\"}",
                "{\"faces\":\"1\",\"plates\":0,\"vehicles\":0,\"detector\":\"d\",\"appVersion\":\"1\"}",
                "{\"faces\":0,\"plates\":0,\"vehicles\":0,\"detector\":\"d\"}",
                "{\"faces\":0,\"plates\":0,\"vehicles\":0,\"detector\":\"d\",\"appVersion\":\"1\",\"boxes\":[{\"kind\":\"person\",\"box\":[0,0,1,1]}]}",
                "{\"faces\":0,\"plates\":0,\"vehicles\":0,\"detector\":\"d\",\"appVersion\":\"1\",\"boxes\":[{\"kind\":\"face\",\"box\":[0,0,1.2,1]}]}",
                "{\"faces\":0,\"plates\":0,\"vehicles\":0,\"detector\":\"d\",\"appVersion\":\"1\",\"boxes\":[{\"kind\":\"face\",\"box\":[0.5,0,0.4,1]}]}",
                "{\"faces\":0,\"plates\":0,\"vehicles\":0,\"detector\":\"d\",\"appVersion\":\"1\",\"boxes\":{}}", "not json"}) {
            assertThatThrownBy(() -> DeviceMaskReport.parse(bad, json)).as(bad).isInstanceOf(ApiException.class);
        }
        String many = "{\"faces\":201,\"plates\":0,\"vehicles\":0,\"detector\":\"d\",\"appVersion\":\"1\",\"boxes\":["
                + String.join(",", java.util.Collections.nCopies(201, "{\"kind\":\"face\",\"box\":[0,0,0.1,0.1]}")) + "]}";
        assertThatThrownBy(() -> DeviceMaskReport.parse(many, json)).isInstanceOf(ApiException.class);
        assertThat(DeviceMaskReport.parse(many.replace("201", "200").replaceFirst(",\\{\"kind\":\"face\",\"box\":\\[0,0,0.1,0.1\\]\\}", ""), json)
                .boxes()).hasSize(200);
        assertThat(DeviceMaskReport.parse("{\"faces\":0,\"plates\":0,\"vehicles\":0,\"detector\":\"d\",\"appVersion\":\"1\"}", json).toStored(false))
                .isEqualTo(Map.of("faces", 0, "plates", 0, "vehicles", 0, "detector", "d", "appVersion", "1"));
    }

    private JsonNode analyze(String photoId) {
        return client.analyze(new AiClient.AnalyzeCall(photoId, MockAiServer.jpeg(2), "image/jpeg", "p-1", Instant.parse("2026-10-15T01:30:00Z"),
                Map.of("faces", 0, "plates", 0, "vehicles", 0, "detector", "d", "app_version", "1"), ""));
    }

    private void assertKind(Kind kind, int status, String code) {
        assertThatThrownBy(() -> analyze("insp_x")).isInstanceOfSatisfying(AiException.class, e -> {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }
}
