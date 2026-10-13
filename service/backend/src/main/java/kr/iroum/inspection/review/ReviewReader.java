package kr.iroum.inspection.review;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import kr.iroum.inspection.media.PrivacyReviewService;
import kr.iroum.inspection.review.ReviewViews.AiClassification;
import kr.iroum.inspection.review.ReviewViews.Analysis;
import kr.iroum.inspection.review.ReviewViews.CapturedBy;
import kr.iroum.inspection.review.ReviewViews.CapturedMember;
import kr.iroum.inspection.review.ReviewViews.CurrentClassification;
import kr.iroum.inspection.review.ReviewViews.FacilityMatch;
import kr.iroum.inspection.review.ReviewViews.Finding;
import kr.iroum.inspection.review.ReviewViews.InspectionClassification;
import kr.iroum.inspection.review.ReviewViews.Media;
import kr.iroum.inspection.review.ReviewViews.Retry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 검토 화면에 쓰는 사진 시도·사진·AI 결과·분류 읽기(업무 API 9·15.4절).
 * <ul>
 * <li>AI 결과는 내부 스냅샷(원문)에서 허용 필드만 만든다: status(OK/NEEDS_REVIEW/RETAKE/ERROR), reviewCategory(RETAKE/ERROR면 null),
 * findings(업무 시설 코드·문제 코드·이름·bbox·근거), facilityMatch, retry. grade·severity·action·senior_message·notes는 반환하지 않는다.</li>
 * <li>분류: AI 원본(OK/NEEDS_REVIEW 결과)과 현재 사람 분류(최신 정정)를 나눈다. AI가 모르는 시설이면 시설 대장 종류를 쓴다.</li>
 * <li>사진: 승인 사본, 없으면(보류 등) 접수 사본의 현재 대체본. 내용 주소는 권한 검사되는 /media/{id}/content뿐이다.</li>
 * </ul>
 */
@Component
public class ReviewReader {

    /** AI taxonomy 시설 → 업무 시설 종류(15.4절). */
    private static final Map<String, String> FACILITY = Map.of("sidewalk", "SIDEWALK", "drain", "DRAIN", "bench", "BENCH", "fence",
            "SAFETY_FENCE", "exercise", "EXERCISE_EQUIPMENT");

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final PrivacyReviewService privacy;
    private final DefectDictionary dictionary;

    public ReviewReader(JdbcTemplate jdbc, JsonMapper json, PrivacyReviewService privacy, DefectDictionary dictionary) {
        this.jdbc = jdbc;
        this.json = json;
        this.privacy = privacy;
        this.dictionary = dictionary;
    }

    /** 사진 시도 기본 행. */
    public record InspectionRow(UUID id, UUID slotId, UUID stopId, UUID patrolId, int attemptNo, Instant capturedAt, Instant receivedAt,
            String receiptStatus, String captureStatus, String retakeReason, boolean deviceMasked, UUID mediaId, UUID approvedMediaId,
            int classificationVersion, String ledgerType, String teamName) {
    }

    public Optional<InspectionRow> inspection(UUID id) {
        return jdbc.query("""
                SELECT i.id, i.capture_slot_id, s.patrol_stop_id, ps.patrol_id, i.attempt_no, i.captured_at, i.received_at, i.receipt_status,
                    i.capture_status, i.retake_reason, i.device_masked, i.media_id, i.approved_media_id, i.classification_version,
                    ps.type_snapshot, p.team_name_snapshot
                FROM inspections i JOIN capture_slots s ON s.id = i.capture_slot_id JOIN patrol_stops ps ON ps.id = s.patrol_stop_id
                JOIN patrols p ON p.id = ps.patrol_id WHERE i.id = ?""",
                (rs, n) -> new InspectionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getInt(5), ts(rs.getObject(6, OffsetDateTime.class)), ts(rs.getObject(7, OffsetDateTime.class)),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getBoolean(11), rs.getObject(12, UUID.class),
                        rs.getObject(13, UUID.class), rs.getInt(14), rs.getString(15), rs.getString(16)),
                id).stream().findFirst();
    }

    /** 촬영 조: 그 조 이름과 촬영 시각에 그 조에 참여 중이던 조원(결석 확정 제외). */
    public CapturedBy capturedBy(InspectionRow i) {
        List<CapturedMember> members = jdbc.query("""
                SELECT DISTINCT m.participant_id, m.name_snapshot, m.seat_no FROM patrol_participations pp
                JOIN patrol_members m ON m.patrol_id = pp.patrol_id AND m.participant_id = pp.participant_id
                WHERE pp.patrol_id = ? AND (pp.leave_reason IS NULL OR pp.leave_reason = 'TRANSFERRED')
                  AND (pp.joined_at IS NULL OR pp.joined_at <= ?) AND (pp.left_at IS NULL OR ? < pp.left_at) AND m.attendance <> 'ABSENT'
                ORDER BY m.seat_no""",
                (rs, n) -> new CapturedMember(rs.getObject(1, UUID.class), rs.getString(2)), i.patrolId(), Timestamp.from(i.capturedAt()),
                Timestamp.from(i.capturedAt()));
        return new CapturedBy(i.teamName(), members);
    }

    /**
     * 검토 작업에서 사람이 판정할 수 있는 사진 시도(REVIEW-06): 그 시도의 현재 사본(승인 포인터, 없으면 접수 사본의 최신 대체본, {@link #media(InspectionRow)}와
     * 같은 기준)이 개인정보 APPROVED인 것. 보류로 풀린 승인 포인터·구도 채택은 재승인으로 복원되지 않지만(D-28), 재승인으로 공개된 현재 사본은
     * 직접 보고 판정할 수 있다. 판정 가능 여부는 구도 채택과 별개다. 매개변수: 검토 작업 ID.
     */
    public static final String REVIEWABLE_INSPECTIONS = """
            WITH RECURSIVE chain(inspection_id, id, next) AS (
                SELECT i.id, m.id, m.superseded_by_media_id FROM inspections i JOIN media m ON m.id = COALESCE(i.approved_media_id, i.media_id)
                WHERE i.id IN (SELECT inspection_id FROM review_task_inspections WHERE review_task_id = ?)
                UNION ALL SELECT c.inspection_id, m.id, m.superseded_by_media_id FROM media m JOIN chain c ON m.id = c.next)
            SELECT c.inspection_id FROM chain c JOIN media m ON m.id = c.id
            WHERE c.next IS NULL AND m.privacy_status = 'APPROVED' AND m.deleted_at IS NULL ORDER BY c.inspection_id""";

    /** 승인 사본, 없으면 접수 사본의 현재 대체본(재가림·재처리 뒤 최신). 사진이 없으면 null. */
    public Media media(InspectionRow i) {
        UUID id = i.approvedMediaId();
        if (id == null && i.mediaId() != null) {
            id = jdbc.queryForObject("""
                    WITH RECURSIVE chain(id, next) AS (
                        SELECT id, superseded_by_media_id FROM media WHERE id = ?
                        UNION ALL SELECT m.id, m.superseded_by_media_id FROM media m JOIN chain c ON m.id = c.next)
                    SELECT id FROM chain WHERE next IS NULL LIMIT 1""", UUID.class, i.mediaId());
        }
        return id == null ? null : media(id);
    }

    public Media media(UUID mediaId) {
        Map<String, Object> m = jdbc.queryForMap("SELECT privacy_status, provider_image_revision, version FROM media WHERE id = ?", mediaId);
        Integer revision = (Integer) m.get("provider_image_revision");
        return new Media(mediaId, revision == null ? 1 : revision, (String) m.get("privacy_status"), "/api/v1/media/" + mediaId + "/content",
                privacy.release(mediaId).orElse(null), ((Number) m.get("version")).longValue());
    }

    /** 스냅샷 하나(analysis_results) → 관리자 분석 객체. */
    public record Snapshot(UUID id, int snapshotNo, JsonNode result, String model, String prompt, Instant observedAt) {
    }

    /** 이 사진 시도의 모든 스냅샷(작업 세대·스냅샷 순). */
    public List<Snapshot> snapshots(UUID inspectionId) {
        return jdbc.query("""
                SELECT r.id, r.snapshot_no, r.result_json::text, r.model_version, r.prompt_version, r.observed_at
                FROM analysis_results r JOIN analysis_jobs j ON j.id = r.analysis_job_id
                WHERE j.inspection_id = ? ORDER BY j.input_version, r.snapshot_no""",
                (rs, n) -> new Snapshot(rs.getObject(1, UUID.class), rs.getInt(2), json.readTree(rs.getString(3)), rs.getString(4),
                        rs.getString(5), ts(rs.getObject(6, OffsetDateTime.class))),
                inspectionId);
    }

    public Analysis analysis(Snapshot s, String ledgerType) {
        JsonNode r = s.result();
        String status = r.path("status").asString("").toUpperCase();
        status = switch (status) {
            case "OK", "NEEDS_REVIEW", "RETAKE", "ERROR" -> status;
            default -> "ERROR";
        };
        String aiType = aiFacility(r);
        String reviewCategory = switch (status) {
            case "OK" -> issues(r).isEmpty() ? "NO_ISSUE_DETECTED" : "REVIEW_REQUIRED";
            case "NEEDS_REVIEW" -> "REVIEW_REQUIRED";
            default -> null; // D-13: RETAKE/ERROR를 이상 미검출로 채우지 않는다
        };
        List<Finding> findings = new ArrayList<>();
        for (JsonNode issue : issues(r)) {
            List<Double> bbox = null;
            if (issue.path("bbox").isArray() && issue.path("bbox").size() == 4) {
                bbox = new ArrayList<>();
                for (JsonNode v : issue.path("bbox")) {
                    bbox.add(Math.max(0, Math.min(1, v.asDouble())));
                }
            }
            String code = issue.path("type").asString("");
            String findingType = aiType != null ? aiType : ledgerType;
            String label = issue.hasNonNull("label") ? issue.path("label").asString() : dictionary.label(findingType, code);
            findings.add(new Finding(findingType, code, label, bbox, issue.path("evidence").asString("")));
        }
        Retry retry = null;
        JsonNode rt = r.get("retry");
        if (rt != null && rt.isObject()) {
            JsonNode next = rt.get("next_at");
            Instant nextAt = next != null && next.isNumber() ? Instant.ofEpochSecond(next.asLong()) : null;
            retry = new Retry(rt.path("pending").asBoolean(false), rt.path("attempts").asInt(0), nextAt, rt.path("gave_up").asBoolean(false));
        }
        return new Analysis(s.id(), s.snapshotNo(), status, reviewCategory, findings,
                new FacilityMatch(aiType, ledgerType, ledgerType.equals(aiType)), retry, s.model() == null ? "" : s.model(),
                s.prompt() == null ? "" : s.prompt(), s.observedAt());
    }

    /** 분류 기준이 되는 최신 AI 결과(OK·NEEDS_REVIEW). 없으면 null. */
    public Snapshot classifiable(List<Snapshot> snapshots) {
        Snapshot last = null;
        for (Snapshot s : snapshots) {
            String status = s.result().path("status").asString("");
            if ("ok".equals(status) || "needs_review".equals(status)) {
                last = s;
            }
        }
        return last;
    }

    public AiClassification aiClassification(Snapshot s, String ledgerType) {
        String type = aiFacility(s.result());
        List<String> codes = new ArrayList<>();
        for (JsonNode issue : issues(s.result())) {
            codes.add(issue.path("type").asString(""));
        }
        return new AiClassification(s.id(), type != null ? type : ledgerType, codes);
    }

    /** 사진 시도의 분류(AI 원본 + 현재 사람 분류). AI 분류 대상 결과가 없으면 null. */
    public InspectionClassification classification(InspectionRow i, List<Snapshot> snapshots) {
        Snapshot base = classifiable(snapshots);
        if (base == null) {
            return null;
        }
        AiClassification ai = aiClassification(base, i.ledgerType());
        CurrentClassification current = new CurrentClassification("AI", ai.facilityType(), ai.issueTypes(), null);
        if (i.classificationVersion() > 0) {
            Map<String, Object> c = jdbc.queryForMap("""
                    SELECT id, new_facility_type, new_issue_types::text AS types FROM classification_corrections
                    WHERE inspection_id = ? AND classification_version = ?""", i.id(), i.classificationVersion());
            current = new CurrentClassification("HUMAN", (String) c.get("new_facility_type"), codes((String) c.get("types")),
                    (UUID) c.get("id"));
        }
        return new InspectionClassification(ai, current, i.classificationVersion());
    }

    public List<String> codes(String jsonArray) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : json.readTree(jsonArray)) {
            out.add(n.asString());
        }
        return out;
    }

    private static List<JsonNode> issues(JsonNode r) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode issues = r.get("issues");
        if (issues != null && issues.isArray()) {
            issues.forEach(out::add);
        }
        return out;
    }

    private static String aiFacility(JsonNode r) {
        return FACILITY.get(r.path("facility").path("type").asString(""));
    }

    public static Instant ts(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
