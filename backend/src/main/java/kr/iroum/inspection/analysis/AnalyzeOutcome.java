package kr.iroum.inspection.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI /analyze 결과의 업무 분류(업무 API 15.2절). status+issues로만 나누고 grade·severity·action은 쓰지 않는다.
 * <ul>
 * <li>ok + issues 없음 → 채택, AI 이상 미검출(표본 검토 대상). ok + issues 있음 → 채택, 이상 의심 검토.</li>
 * <li>needs_review → 검토 인계(시설 안 보임 포함, 품질 횟수 없음). retake → 품질 실패. error → 사진 처리 실패(저장 없음).</li>
 * </ul>
 * 시설 대장과 AI 시설 종류가 다르면 검토 대상(FACILITY_MISMATCH)을 더하되 시설 ID·부서는 바꾸지 않는다.
 */
public final class AnalyzeOutcome {

    /** AI taxonomy 시설 → 업무 시설 종류(업무 API 15.4절). 그 밖의 값(unknown 등)은 비교하지 않는다. */
    private static final Map<String, String> FACILITY = Map.of("sidewalk", "SIDEWALK", "drain", "DRAIN", "bench", "BENCH", "fence",
            "SAFETY_FENCE", "exercise", "EXERCISE_EQUIPMENT");

    private AnalyzeOutcome() {
    }

    public static String status(JsonNode result) {
        JsonNode s = result.get("status");
        return s != null && s.isString() ? s.asString() : "";
    }

    public static boolean hasStoredImage(JsonNode result) {
        JsonNode url = result.get("masked_image_url");
        return url != null && url.isString() && !url.asString().isBlank();
    }

    public static boolean hasIssues(JsonNode result) {
        JsonNode issues = result.get("issues");
        return issues != null && issues.isArray() && !issues.isEmpty();
    }

    public static boolean retryPending(JsonNode result) {
        JsonNode retry = result.get("retry");
        return retry != null && retry.isObject() && retry.path("pending").asBoolean(false);
    }

    /** 품질 문제(AI quality.problems: blur·dark·out_of_frame) → retakeReason. 알 수 없으면 BLUR. */
    public static String retakeReason(JsonNode result) {
        JsonNode problems = result.path("quality").path("problems");
        if (problems.isArray()) {
            for (JsonNode p : problems) {
                String reason = switch (p.asString("")) {
                    case "blur" -> "BLUR";
                    case "dark" -> "DARK";
                    case "out_of_frame" -> "OUT_OF_FRAME";
                    default -> null;
                };
                if (reason != null) {
                    return reason;
                }
            }
        }
        return "BLUR";
    }

    /** AI가 알아본 시설 종류(업무 코드)가 대장과 다르면 true. AI가 모르는 시설·추가 시설 종류는 비교하지 않는다. */
    public static boolean facilityMismatch(JsonNode result, String ledgerType) {
        String ai = FACILITY.get(result.path("facility").path("type").asString(""));
        return ai != null && FACILITY.containsValue(ledgerType) && !ai.equals(ledgerType);
    }

    /** ok/needs_review 결과의 검토 원인(review_tasks.trigger_reason). 순서는 의미 없음. */
    public static List<String> reviewTriggers(JsonNode result, String ledgerType, boolean sampleNoIssue) {
        List<String> out = new ArrayList<>();
        String status = status(result);
        if ("needs_review".equals(status)) {
            out.add("AI_NEEDS_REVIEW");
        } else if ("ok".equals(status)) {
            if (hasIssues(result)) {
                out.add("ISSUE_SUSPECTED");
            } else if (sampleNoIssue) {
                out.add("NO_ISSUE_SAMPLE");
            }
        }
        if (("ok".equals(status) || "needs_review".equals(status)) && facilityMismatch(result, ledgerType)) {
            out.add("FACILITY_MISMATCH");
        }
        return out;
    }

    /** 내부 스냅샷용 원문: 사진 주소만 뺀다(DB 설계 7절). 등급 등은 내부 보존하되 사용자 응답에 쓰지 않는다. */
    public static JsonNode snapshot(JsonNode result) {
        JsonNode copy = result.deepCopy();
        if (copy instanceof ObjectNode o) {
            o.remove("masked_image_url");
        }
        return copy;
    }

    /** 개인정보 보고(meta.privacy)의 개수·검출기 정보만. 위치 목록(boxes)은 저장하지 않는다. */
    public static JsonNode privacyReport(JsonNode result) {
        JsonNode privacy = result.path("meta").path("privacy");
        if (!privacy.isObject()) {
            return null;
        }
        ObjectNode copy = (ObjectNode) privacy.deepCopy();
        copy.remove("boxes");
        if (copy.get("device") instanceof ObjectNode device) {
            device.remove("boxes");
        }
        return copy;
    }
}
