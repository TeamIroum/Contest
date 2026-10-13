package kr.iroum.inspection.inspection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 앱 마스킹 보고(deviceMaskReport, 업무 API 6.3·15.1절, AI 계약 2.0 2026-10-12 추가분).
 * faces/plates/vehicles는 0 이상 정수, detector·appVersion은 필수 문자열이다. boxes는 선택이며 앱이 가린 위치
 * {kind: face|plate|vehicle, box: [x1, y1, x2, y2]}(0~1 비율, x1≤x2·y1≤y2), 최대 200개다.
 * AI에는 snake_case(app_version)로 바꿔 넘기고 boxes는 같은 구조로 전달한다. 마스킹 실패를 0건 성공으로 위장하지 않도록
 * 개수 누락·음수·문자열 숫자는 거절한다.
 */
public record DeviceMaskReport(int faces, int plates, int vehicles, String detector, String appVersion, List<Box> boxes) {

    public static final int MAX_BOXES = 200;
    private static final Set<String> KINDS = Set.of("face", "plate", "vehicle");

    public record Box(String kind, double x1, double y1, double x2, double y2) {
    }

    public static DeviceMaskReport parse(String raw, JsonMapper json) {
        JsonNode node;
        try {
            node = raw == null ? null : json.readTree(raw);
        } catch (RuntimeException e) {
            throw invalid("JSON 객체 문자열이어야 합니다.");
        }
        if (node == null || !node.isObject()) {
            throw invalid("JSON 객체 문자열이어야 합니다.");
        }
        int faces = count(node, "faces");
        int plates = count(node, "plates");
        int vehicles = count(node, "vehicles");
        String detector = text(node, "detector");
        String appVersion = text(node, "appVersion");
        List<Box> boxes = new ArrayList<>();
        JsonNode list = node.get("boxes");
        if (list != null && !list.isNull()) {
            if (!list.isArray() || list.size() > MAX_BOXES) {
                throw invalid("boxes는 최대 " + MAX_BOXES + "개의 배열이어야 합니다.");
            }
            for (JsonNode b : list) {
                JsonNode kind = b.get("kind");
                JsonNode box = b.get("box");
                if (!b.isObject() || kind == null || !kind.isString() || !KINDS.contains(kind.asString()) || box == null || !box.isArray()
                        || box.size() != 4) {
                    throw invalid("boxes 항목은 {kind: face|plate|vehicle, box: [x1, y1, x2, y2]}입니다.");
                }
                double[] v = new double[4];
                for (int i = 0; i < 4; i++) {
                    JsonNode n = box.get(i);
                    if (n == null || !n.isNumber() || n.asDouble() < 0 || n.asDouble() > 1) {
                        throw invalid("boxes 좌표는 0~1 비율 숫자입니다.");
                    }
                    v[i] = n.asDouble();
                }
                if (v[0] > v[2] || v[1] > v[3]) {
                    throw invalid("boxes 좌표는 x1≤x2, y1≤y2여야 합니다.");
                }
                boxes.add(new Box(kind.asString(), v[0], v[1], v[2], v[3]));
            }
        }
        return new DeviceMaskReport(faces, plates, vehicles, detector, appVersion, List.copyOf(boxes));
    }

    /** AI device_mask_report(snake_case). boxes는 보냈을 때만 넣는다(보내지 않으면 AI 계약상 이전과 같은 동작). */
    public Map<String, Object> toAi(boolean includeBoxes) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("faces", faces);
        out.put("plates", plates);
        out.put("vehicles", vehicles);
        out.put("detector", detector);
        out.put("app_version", appVersion);
        if (includeBoxes) {
            out.put("boxes", boxes.stream().map(b -> Map.of("kind", b.kind(), "box", List.of(b.x1(), b.y1(), b.x2(), b.y2()))).toList());
        }
        return out;
    }

    /** 업무 저장용(camelCase 원 보고). 실명·연락처 없음. */
    public Map<String, Object> toStored(boolean includeBoxes) {
        Map<String, Object> out = new LinkedHashMap<>(toAi(includeBoxes));
        out.remove("app_version");
        out.put("appVersion", appVersion);
        return out;
    }

    private static int count(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || !n.isIntegralNumber() || n.asLong() < 0 || n.asLong() > 10_000) {
            throw invalid(field + "는 0 이상 정수입니다.");
        }
        return n.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || !n.isString() || n.asString().isBlank() || n.asString().length() > 100) {
            throw invalid(field + "는 필수 문자열입니다.");
        }
        return n.asString();
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null,
                List.of(new ErrorResponse.FieldError("deviceMaskReport", "INVALID_FORMAT", message)));
    }
}
