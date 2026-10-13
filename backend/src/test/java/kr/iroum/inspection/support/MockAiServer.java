package kr.iroum.inspection.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * AI 판독 모듈 v2.0 계약 기반 mock(실제 AI 서버가 아님). fixtures는 origin/main 3341bf3 사본(test resources ai-contract-3341bf3).
 * 2026-10-12 전달 규칙을 따른다: 오류 본문 {detail, code}, 4xx·error·사진 없는 retake는 저장 안 함, 저장된 결과는 같은 photo_id에
 * meta.replayed=true로 재현, 보류 사진은 410 PHOTO_HIDDEN, audit ok는 JSON 불리언.
 * 테스트가 다음 응답을 정한다(script). 받은 요청은 기록해 필드 변환(app_version·boxes·location_tags·lat/lon 미전송)을 검사한다.
 */
public class MockAiServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 받은 /analyze 요청: 텍스트 필드와 사진 바이트, 헤더. */
    public record AnalyzeRequest(Map<String, String> fields, byte[] image, String apiKey) {
    }

    /** 한 번의 응답 지시. fixture가 있으면 photo_id·주소를 바꿔 쓴다. */
    public record Reply(int status, String fixture, String errorCode, boolean dropConnection, long delayMillis, String retakeOverride) {

        public static Reply fixture(String name) {
            return new Reply(200, name, null, false, 0, null);
        }

        public static Reply error(int status, String code) {
            return new Reply(status, null, code, false, 0, null);
        }

        public static Reply drop() {
            return new Reply(0, null, null, true, 0, null);
        }

        public Reply delayed(long millis) {
            return new Reply(status, fixture, errorCode, dropConnection, millis, retakeOverride);
        }
    }

    private final HttpServer server;
    private final Deque<Reply> analyzeScript = new ArrayDeque<>();
    private final Map<String, ObjectNode> results = new ConcurrentHashMap<>();
    private final Map<String, Integer> revisions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> hidden = new ConcurrentHashMap<>();
    private final List<AnalyzeRequest> analyzeRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> imageRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> auditRequests = Collections.synchronizedList(new ArrayList<>());
    private final Deque<Reply> auditScript = new ArrayDeque<>();
    private final Deque<Reply> imageScript = new ArrayDeque<>();
    private final Deque<Reply> privacyScript = new ArrayDeque<>();
    private volatile CountDownLatch privacyGate;
    private final List<Map<String, String>> privacyRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<ObjectNode> events = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger analyzeCalls = new AtomicInteger();
    private volatile String requiredKey = "";
    private volatile boolean healthOk = true;
    private volatile CountDownLatch analyzeGate;
    private volatile CountDownLatch nextAuditGate;

    public MockAiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized void reset() {
        analyzeScript.clear();
        auditScript.clear();
        imageScript.clear();
        privacyScript.clear();
        privacyGate = null;
        privacyRequests.clear();
        results.clear();
        revisions.clear();
        hidden.clear();
        analyzeRequests.clear();
        imageRequests.clear();
        auditRequests.clear();
        events.clear();
        analyzeCalls.set(0);
        requiredKey = "";
        healthOk = true;
        analyzeGate = null;
        nextAuditGate = null;
    }

    public synchronized MockAiServer nextAnalyze(Reply... replies) {
        analyzeScript.addAll(List.of(replies));
        return this;
    }

    public synchronized MockAiServer nextAudit(Reply... replies) {
        auditScript.addAll(List.of(replies));
        return this;
    }

    /** 다음 /privacy/mask 응답들. fixture 이름이면 그 응답(get_privacy_mask_reference 형식), 오류면 HTTP 오류. 비어 있으면 status ok. */
    public synchronized MockAiServer nextPrivacyMask(Reply... replies) {
        privacyScript.addAll(List.of(replies));
        return this;
    }

    /** 다음 /privacy/mask 요청들이 받은 뒤(요청 기록 후) 이 래치가 열릴 때까지 응답을 미룬다. 경합 검증용. */
    public void holdPrivacyMask(CountDownLatch gate) {
        privacyGate = gate;
    }

    /** /privacy/mask로 받은 필드(사진 바이트 제외). */
    public List<Map<String, String>> privacyRequests() {
        return privacyRequests;
    }

    public synchronized MockAiServer nextImage(Reply... replies) {
        imageScript.addAll(List.of(replies));
        return this;
    }

    public void requireKey(String key) {
        this.requiredKey = key;
    }

    public void healthOk(boolean ok) {
        this.healthOk = ok;
    }

    /** 다음 /audit/masking 요청 하나만 이 래치를 기다리게 한다(요청 기록 뒤, 상태 반영 전). 전송 순서 경합 검증용. */
    public void holdNextAudit(CountDownLatch gate) {
        this.nextAuditGate = gate;
    }

    /** /analyze가 이 래치를 기다리게 한다(동시 제출 검증용). */
    public void holdAnalyze(CountDownLatch gate) {
        this.analyzeGate = gate;
    }

    public List<AnalyzeRequest> analyzeRequests() {
        return List.copyOf(analyzeRequests);
    }

    public int analyzeCalls() {
        return analyzeCalls.get();
    }

    public List<String> imageRequests() {
        return List.copyOf(imageRequests);
    }

    public List<Map<String, Object>> auditRequests() {
        return List.copyOf(auditRequests);
    }

    /** 저장된 결과를 바꾸고 result_updated 알림을 남긴다(재판독 완료 흉내). */
    public void finishReanalysis(String photoId, String fixture) {
        ObjectNode node = load(fixture, photoId);
        node.put("masked_image_url", "/images/" + photoId + "_masked.jpg");
        results.put(photoId, node);
        event("result_updated", photoId, revisions.getOrDefault(photoId, 1));
    }

    /** 재가림: 사진 버전을 올리고 image_remasked 알림. */
    public void remask(String photoId) {
        int rev = revisions.merge(photoId, 1, Integer::sum);
        event("image_remasked", photoId, rev);
    }

    /** 업무 백엔드가 아닌 다른 경로(검증 콘솔 등)의 가림 점검 흉내. 실제 AI처럼 상태가 바뀔 때만 알림(reason은 그 경로의 메모). */
    public void hide(String photoId) {
        if (hidden.put(photoId, true) == null) {
            event("image_hidden", photoId, revisions.getOrDefault(photoId, 1), "검증 콘솔 점검");
        }
    }

    public void unhide(String photoId) {
        if (hidden.remove(photoId) != null) {
            event("image_unhidden", photoId, revisions.getOrDefault(photoId, 1), "검증 콘솔 점검");
        }
    }

    /** 알림 없이 보류 상태만 바꾼다(알림을 따로 넣는 시험용). */
    public void setHidden(String photoId, boolean value) {
        if (value) {
            hidden.put(photoId, true);
        } else {
            hidden.remove(photoId);
        }
    }

    public boolean hidden(String photoId) {
        return hidden.getOrDefault(photoId, false);
    }

    public synchronized void event(String type, String photoId, int revision) {
        event(type, photoId, revision, null);
    }

    /** reason: 실제 AI는 가림 점검 요청의 note를 넣는다(그 밖의 알림은 null 또는 AI 내부 사유). */
    public synchronized void event(String type, String photoId, int revision, String reason) {
        ObjectNode e = JSON.createObjectNode();
        e.put("seq", events.size() + 1);
        e.put("type", type);
        e.put("photo_id", photoId);
        e.put("at", "2026-10-15T10:31:00+09:00");
        e.put("image_revision", revision);
        e.put("reason", reason);
        events.add(e);
    }

    /** 1x1이 아닌 작은 실제 JPEG(가상 사진). */
    public static byte[] jpeg(int seed) {
        try {
            BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 48; y++) {
                    img.setRGB(x, y, (x * 4 + seed * 31) << 16 | (y * 5 + seed) << 8 | (seed * 7 & 0xff));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectNode load(String fixture, String photoId) {
        try (InputStream in = MockAiServer.class.getResourceAsStream("/ai-contract-3341bf3/" + fixture + ".json")) {
            ObjectNode node = (ObjectNode) JSON.readTree(in);
            node.put("photo_id", photoId);
            if (node.hasNonNull("masked_image_url")) {
                node.put("masked_image_url", "/images/" + photoId + "_masked.jpg");
            }
            return node;
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("fixture 없음: " + fixture, e);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/health")) {
                ObjectNode h = JSON.createObjectNode();
                h.put("ok", healthOk);
                h.put("contract_version", "2.0");
                h.putObject("detectors").put("plate", healthOk ? "ok" : "unavailable");
                send(ex, 200, h);
                return;
            }
            if (!requiredKey.isEmpty() && !requiredKey.equals(ex.getRequestHeaders().getFirst("X-API-Key"))) {
                sendError(ex, 401, "UNAUTHORIZED");
                return;
            }
            if (path.equals("/analyze") && ex.getRequestMethod().equals("POST")) {
                analyze(ex);
            } else if (path.startsWith("/images/") && path.endsWith("_masked.jpg")) {
                image(ex, path.substring("/images/".length(), path.length() - "_masked.jpg".length()));
            } else if (path.startsWith("/results/")) {
                ObjectNode r = results.get(path.substring("/results/".length()));
                if (r == null) {
                    sendError(ex, 404, "RESULT_NOT_FOUND");
                } else {
                    send(ex, 200, r);
                }
            } else if (path.equals("/events")) {
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                long after = Long.parseLong(q.getOrDefault("after", "0"));
                int limit = Integer.parseInt(q.getOrDefault("limit", "100"));
                ObjectNode page = JSON.createObjectNode();
                var arr = page.putArray("events");
                long last = 0;
                synchronized (this) {
                    for (ObjectNode e : events) {
                        long seq = e.get("seq").asLong();
                        last = Math.max(last, seq);
                        if (seq > after && arr.size() < limit) {
                            arr.add(e);
                        }
                    }
                }
                page.put("next_after", arr.isEmpty() ? after : arr.get(arr.size() - 1).get("seq").asLong());
                page.put("last_seq", last);
                send(ex, 200, page);
            } else if (path.equals("/privacy/mask") && ex.getRequestMethod().equals("POST")) {
                privacyMask(ex);
            } else if (path.startsWith("/audit/masking/")) {
                audit(ex, path.substring("/audit/masking/".length()));
            } else {
                sendError(ex, 404, "NOT_FOUND");
            }
        } catch (RuntimeException e) {
            sendError(ex, 500, "INTERNAL");
        }
    }

    private void analyze(HttpExchange ex) throws IOException {
        analyzeCalls.incrementAndGet();
        Map<String, Object> parts = Multipart.parse(ex);
        Map<String, String> fields = new LinkedHashMap<>();
        parts.forEach((k, v) -> {
            if (v instanceof String s) {
                fields.put(k, s);
            }
        });
        byte[] image = (byte[]) parts.get("image");
        analyzeRequests.add(new AnalyzeRequest(fields, image, ex.getRequestHeaders().getFirst("X-API-Key")));
        CountDownLatch gate = analyzeGate;
        if (gate != null) {
            try {
                gate.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String photoId = fields.get("photo_id");
        ObjectNode stored = results.get(photoId);
        if (stored != null) {
            ObjectNode replay = stored.deepCopy();
            ((ObjectNode) replay.with("meta")).put("replayed", true);
            send(ex, 200, replay);
            return;
        }
        Reply reply;
        synchronized (this) {
            reply = analyzeScript.isEmpty() ? Reply.fixture("analyze_01_normal") : analyzeScript.poll();
        }
        if (reply.delayMillis() > 0) {
            sleep(reply.delayMillis());
        }
        if (reply.dropConnection()) {
            ex.close();
            return;
        }
        if (reply.fixture() == null) {
            sendError(ex, reply.status(), reply.errorCode());
            return;
        }
        ObjectNode body = load(reply.fixture(), photoId);
        String status = body.get("status").asText();
        boolean saved = body.hasNonNull("masked_image_url") && !status.equals("error");
        if (saved) {
            revisions.putIfAbsent(photoId, body.path("image_revision").asInt(1));
            body.put("image_revision", revisions.get(photoId));
            results.put(photoId, body.deepCopy());
        }
        send(ex, 200, body);
    }

    private void privacyMask(HttpExchange ex) throws IOException {
        Map<String, Object> parts = Multipart.parse(ex);
        Map<String, String> fields = new LinkedHashMap<>();
        parts.forEach((k, v) -> {
            if (v instanceof String s) {
                fields.put(k, s);
            }
        });
        privacyRequests.add(fields);
        CountDownLatch gate = privacyGate;
        if (gate != null) {
            try {
                gate.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Reply reply;
        synchronized (this) {
            reply = privacyScript.isEmpty() ? Reply.fixture("get_privacy_mask_reference") : privacyScript.poll();
        }
        if (reply.delayMillis() > 0) {
            sleep(reply.delayMillis());
        }
        if (reply.dropConnection()) {
            ex.close();
            return;
        }
        if (reply.fixture() == null) {
            sendError(ex, reply.status(), reply.errorCode());
            return;
        }
        String mediaId = fields.get("media_id");
        String photoId = "m_" + mediaId;
        ObjectNode body = load(reply.fixture(), photoId);
        body.put("media_id", mediaId);
        body.put("purpose", fields.get("purpose"));
        if (reply.retakeOverride() != null) {
            body.put("status", reply.retakeOverride());
            body.putNull("masked_image_url");
        }
        if ("ok".equals(body.path("status").asText())) {
            revisions.putIfAbsent(photoId, 1);
            body.put("image_revision", revisions.get(photoId));
            results.put(photoId, body.deepCopy());
        }
        send(ex, 200, body);
    }

    private void image(HttpExchange ex, String photoId) throws IOException {
        imageRequests.add(photoId);
        Reply reply;
        synchronized (this) {
            reply = imageScript.poll();
        }
        if (reply != null && reply.dropConnection()) {
            ex.close();
            return;
        }
        if (reply != null && reply.fixture() == null) {
            sendError(ex, reply.status(), reply.errorCode());
            return;
        }
        if (hidden.getOrDefault(photoId, false)) {
            sendError(ex, 410, "PHOTO_HIDDEN");
            return;
        }
        if (!results.containsKey(photoId)) {
            sendError(ex, 404, "PHOTO_NOT_FOUND");
            return;
        }
        int rev = revisions.getOrDefault(photoId, 1);
        byte[] bytes = jpeg(photoId.hashCode() + rev);
        ex.getResponseHeaders().add("Content-Type", "image/jpeg");
        ex.getResponseHeaders().add("X-Image-Revision", String.valueOf(rev));
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void audit(HttpExchange ex, String id) throws IOException {
        JsonNode body;
        try (InputStream in = ex.getRequestBody()) {
            body = JSON.readTree(in.readAllBytes());
        }
        Map<String, Object> rec = new HashMap<>();
        rec.put("id", id);
        rec.put("body", body);
        auditRequests.add(rec);
        CountDownLatch gate;
        synchronized (this) {
            gate = nextAuditGate;
            nextAuditGate = null;
        }
        if (gate != null) {
            try {
                gate.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Reply reply;
        synchronized (this) {
            reply = auditScript.poll();
        }
        if (reply != null && reply.dropConnection()) {
            ex.close();
            return;
        }
        if (reply != null && reply.fixture() == null) {
            sendError(ex, reply.status(), reply.errorCode());
            return;
        }
        if (body == null || !body.isObject() || !body.has("ok") || !body.get("ok").isBoolean()) {
            sendError(ex, 400, "INVALID_AUDIT");
            return;
        }
        boolean ok = body.get("ok").asBoolean();
        boolean wasHidden = hidden.getOrDefault(id, false);
        if (ok) {
            hidden.remove(id);
        } else {
            hidden.put(id, true);
        }
        // 실제 AI(c96bd57 ai/activity.py record_audit)와 같이 상태가 바뀔 때만 알림을 남긴다: 공개→보류 image_hidden, 보류→공개
        // image_unhidden. 이미 보류인 사진의 ok:false, 공개인 사진의 ok:true는 알림이 없다. 알림의 reason은 요청의 note 그대로다.
        String note = body.hasNonNull("note") ? body.get("note").asText() : null;
        if (!ok && !wasHidden) {
            event("image_hidden", id, revisions.getOrDefault(id, 1), note);
        } else if (ok && wasHidden) {
            event("image_unhidden", id, revisions.getOrDefault(id, 1), note);
        }
        ObjectNode resp = JSON.createObjectNode();
        resp.put("ok", ok);
        resp.put("at", "2026-10-15T10:40:00+09:00");
        resp.put("hidden", !ok);
        if (reply != null && reply.fixture() != null && reply.fixture().equals("hidden-stays")) {
            resp.put("hidden", true);
        }
        send(ex, 200, resp);
    }

    private static void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendError(HttpExchange ex, int status, String code) throws IOException {
        ObjectNode body = JSON.createObjectNode();
        body.put("detail", "가상 오류");
        body.put("code", code);
        send(ex, status, body);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        for (String kv : raw.split("&")) {
            String[] p = kv.split("=", 2);
            out.put(p[0], p.length > 1 ? p[1] : "");
        }
        return out;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** 최소 multipart 파서(mock 전용). */
    static final class Multipart {
        static Map<String, Object> parse(HttpExchange ex) throws IOException {
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            String boundary = "--" + type.substring(type.indexOf("boundary=") + 9);
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readAllBytes();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            byte[] sep = boundary.getBytes(StandardCharsets.UTF_8);
            int pos = indexOf(body, sep, 0);
            while (pos >= 0) {
                int start = pos + sep.length;
                if (start + 2 <= body.length && body[start] == '-' && body[start + 1] == '-') {
                    break;
                }
                start += 2;
                int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), start);
                String headers = new String(body, start, headerEnd - start, StandardCharsets.UTF_8);
                int next = indexOf(body, sep, headerEnd + 4);
                byte[] content = java.util.Arrays.copyOfRange(body, headerEnd + 4, next - 2);
                String name = headers.replaceAll("(?s).*?\\bname=\"([^\"]+)\".*", "$1");
                out.put(name, headers.contains("filename=") ? content : new String(content, StandardCharsets.UTF_8));
                pos = next;
            }
            return out;
        }

        private static int indexOf(byte[] data, byte[] pattern, int from) {
            outer:
            for (int i = from; i <= data.length - pattern.length; i++) {
                for (int j = 0; j < pattern.length; j++) {
                    if (data[i + j] != pattern[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }
}
