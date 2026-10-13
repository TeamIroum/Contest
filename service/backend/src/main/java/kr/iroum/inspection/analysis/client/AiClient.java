package kr.iroum.inspection.analysis.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 판독 모듈 v2.0 클라이언트(AI 원본 계약, AI 연동 계약 2026-10-12 절). 업무 어댑터만 이 클래스를 쓴다.
 * <ul>
 * <li>API 키는 서버 설정(AI_API_KEY)으로만 받고 응답·로그에 남기지 않는다. 사진 URL·원문도 로그에 남기지 않는다.</li>
 * <li>오류는 HTTP 상태와 본문 code로 분기한다(detail 문장으로 분기하지 않음). 연결 실패·시간 초과·5xx·429는 일시 장애(TRANSIENT),
 * 그 밖의 4xx는 요청 구성·대상 오류(REJECTED)다. AI의 HTTP 오류를 업무 HTTP 코드로 그대로 전달하지 않는다.</li>
 * <li>HTTP 200 + status:error는 오류가 아니라 판독 결과로 돌려준다(접수 성공이 아님은 호출자가 판단).</li>
 * </ul>
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);
    /** AI 계약: ^[A-Za-z0-9_-]{1,80}$, 소문자 m_ 시작 금지(AI 내부 예약). */
    private static final Pattern PHOTO_ID = Pattern.compile("^[A-Za-z0-9_-]{1,80}$");

    public enum Kind {
        TRANSIENT, REJECTED
    }

    /** AI 호출 실패. code는 AI 오류 본문의 code(내부 기록 전용), 연결 실패면 null. */
    public static class AiException extends RuntimeException {
        private final Kind kind;
        private final int status;
        private final String code;

        public AiException(Kind kind, int status, String code, String message) {
            super(message);
            this.kind = kind;
            this.status = status;
            this.code = code;
        }

        public Kind kind() {
            return kind;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public boolean transientFailure() {
            return kind == Kind.TRANSIENT;
        }
    }

    /** /analyze 입력. deviceMaskReport는 AI 형식(snake_case), locationTags는 쉼표 문자열(없으면 빈 문자열). lat/lon은 보내지 않는다. */
    public record AnalyzeCall(String photoId, byte[] image, String contentType, String participantId, Instant capturedAt,
            Map<String, Object> deviceMaskReport, String locationTags) {
    }

    public record MaskedImage(byte[] bytes, int revision, String contentType) {
    }

    private final HttpClient http;
    private final URI base;
    private final String apiKey;
    private final Duration requestTimeout;
    private final JsonMapper json;

    public AiClient(@Value("${inspection.ai.base-url:http://localhost:8000}") String baseUrl,
            @Value("${inspection.ai.api-key:}") String apiKey,
            @Value("${inspection.ai.connect-timeout:5s}") Duration connectTimeout,
            @Value("${inspection.ai.request-timeout:30s}") Duration requestTimeout, JsonMapper json) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static boolean validPhotoId(String id) {
        return id != null && PHOTO_ID.matcher(id).matches() && !id.startsWith("m_");
    }

    /** POST /analyze(multipart). 200이면 판독 JSON(status ok/needs_review/retake/error)을 돌려준다. */
    public JsonNode analyze(AnalyzeCall call) {
        if (!validPhotoId(call.photoId())) {
            throw new IllegalArgumentException("AI photo_id 형식 오류");
        }
        Multipart body = new Multipart();
        body.file("image", call.photoId() + (call.contentType().equals("image/png") ? ".png" : ".jpg"), call.contentType(), call.image());
        body.field("photo_id", call.photoId());
        body.field("participant_id", call.participantId());
        body.field("captured_at", call.capturedAt().toString());
        body.field("device_masked", "true");
        body.field("device_mask_report", json.writeValueAsString(call.deviceMaskReport()));
        body.field("location_tags", call.locationTags() == null ? "" : call.locationTags());
        HttpRequest request = request("analyze").header("Content-Type", body.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes())).build();
        return jsonOf(send(request, "analyze"));
    }

    /**
     * POST /privacy/mask(multipart, AI 계약 "참고·보수 사진"). 판독하지 않고 얼굴·번호판만 검사·가림. 응답 status가 ok일 때만 검사 완료본이 저장된다.
     * AI 저장 이름은 m_&lt;mediaId&gt;이며 같은 ID에 다른 사진이면 409.
     */
    public JsonNode privacyMask(String mediaId, byte[] image, String contentType, String purpose, Map<String, Object> deviceMaskReport) {
        Multipart body = new Multipart();
        body.file("image", mediaId + (contentType.equals("image/png") ? ".png" : ".jpg"), contentType, image);
        body.field("media_id", mediaId);
        body.field("purpose", purpose);
        body.field("device_masked", "true");
        body.field("device_mask_report", json.writeValueAsString(deviceMaskReport));
        HttpRequest request = request("privacy/mask").header("Content-Type", body.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes())).build();
        return jsonOf(send(request, "privacy-mask"));
    }

    /** GET /images/{photo_id}_masked.jpg. 410 PHOTO_HIDDEN·404는 REJECTED로 던진다. */
    public MaskedImage maskedImage(String photoId) {
        HttpResponse<byte[]> resp = send(request("images/" + enc(photoId) + "_masked.jpg").GET().build(), "images");
        int revision = resp.headers().firstValue("X-Image-Revision").map(Integer::parseInt).orElse(1);
        String type = resp.headers().firstValue("Content-Type").orElse("image/jpeg");
        return new MaskedImage(resp.body(), revision, type.startsWith("image/png") ? "image/png" : "image/jpeg");
    }

    /** GET /results/{photo_id}. 결과가 없으면(404) 빈 값. */
    public Optional<JsonNode> result(String photoId) {
        try {
            return Optional.of(jsonOf(send(request("results/" + enc(photoId)).GET().build(), "results")));
        } catch (AiException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** GET /events?after=&limit=. {events, next_after, last_seq}. */
    public JsonNode events(long after, int limit) {
        return jsonOf(send(request("events?after=" + after + "&limit=" + limit).GET().build(), "events"));
    }

    /** POST /audit/masking/{id}. ok는 JSON 불리언이며 reviewer(실명 가능성)는 보내지 않는다. */
    public JsonNode auditMasking(String id, boolean ok) {
        return auditMasking(id, ok, null);
    }

    /**
     * note에는 개인정보가 없는 연결 표지만 넣는다(실명·연락처·사유 원문 금지). AI(c96bd57 activity.record_audit)는 이 요청으로 보류 상태가 바뀌면
     * 그 알림의 reason에 note를 그대로 남긴다. 이 동작은 AI 계약에 명시돼 있지 않다(04단계 문서 7절 계약 요청).
     */
    public JsonNode auditMasking(String id, boolean ok, String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", ok);
        if (note != null) {
            payload.put("note", note);
        }
        HttpRequest request = request("audit/masking/" + enc(id)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        return jsonOf(send(request, "audit"));
    }

    /** GET /health(인증 없음). ok=false는 마스킹 검출기 장애다. */
    public JsonNode health() {
        return jsonOf(send(HttpRequest.newBuilder(base.resolve("health")).timeout(requestTimeout).GET().build(), "health"));
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).timeout(requestTimeout);
        if (!apiKey.isBlank()) {
            b.header("X-API-Key", apiKey);
        }
        return b;
    }

    private HttpResponse<byte[]> send(HttpRequest request, String operation) {
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            log.warn("AI {} 연결 실패: {}", operation, e.getClass().getSimpleName());
            throw new AiException(Kind.TRANSIENT, 0, null, "AI 연결 실패");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(Kind.TRANSIENT, 0, null, "AI 호출 중단");
        }
        int status = resp.statusCode();
        if (status >= 200 && status < 300) {
            return resp;
        }
        String code = errorCode(resp.body());
        Kind kind = status >= 500 || status == 429 ? Kind.TRANSIENT : Kind.REJECTED;
        log.warn("AI {} 오류: HTTP {} code={}", operation, status, code);
        throw new AiException(kind, status, code, "AI 오류 " + status);
    }

    private String errorCode(byte[] body) {
        try {
            JsonNode node = json.readTree(body);
            JsonNode code = node == null ? null : node.get("code");
            return code != null && code.isString() ? code.asString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonNode jsonOf(HttpResponse<byte[]> resp) {
        try {
            JsonNode node = json.readTree(resp.body());
            if (node == null || !(node.isObject() || node.isArray())) {
                throw new AiException(Kind.TRANSIENT, resp.statusCode(), "INVALID_RESPONSE", "AI 응답 형식 오류");
            }
            return node;
        } catch (AiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AiException(Kind.TRANSIENT, resp.statusCode(), "INVALID_RESPONSE", "AI 응답 형식 오류");
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 메모리 안에서만 만드는 multipart 본문(디스크 임시 파일 없음). */
    static final class Multipart {
        private final String boundary = "----itda" + UUID.randomUUID().toString().replace("-", "");
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void field(String name, String value) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
            write(value);
            write("\r\n");
        }

        void file(String name, String filename, String contentType, byte[] data) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
                    + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
            out.writeBytes(data);
            write("\r\n");
        }

        String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        byte[] bytes() {
            write("--" + boundary + "--\r\n");
            return out.toByteArray();
        }

        private void write(String s) {
            out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
