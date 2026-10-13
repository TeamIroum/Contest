package kr.iroum.inspection.review;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse.FieldError;
import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.review.ReviewReader.InspectionRow;
import kr.iroum.inspection.review.ReviewViews.Actor;
import kr.iroum.inspection.review.ReviewViews.InspectionClassification;
import kr.iroum.inspection.review.ReviewViews.Page;
import tools.jackson.databind.json.JsonMapper;

/**
 * 분류 정정(업무 API 9.2절, D-08). 시설 담당자가 본인 관할 사진의 시설 종류·문제 유형을 사유와 함께 정정한다.
 * <ul>
 * <li>AI 스냅샷은 바꾸지 않고 정정 이력(classification_corrections)을 추가한다. 사진별 사람 분류 버전(classification_version)은 0에서 시작해
 * 정정마다 1씩 오른다. 요청 버전이 현재와 다르면 409 VERSION_CONFLICT(details.currentVersion).</li>
 * <li>analysisResultId는 같은 사진의 판독 결과(OK·NEEDS_REVIEW)여야 한다. 시설 종류·문제 코드 조합은 업무 사전({@link DefectDictionary})으로 검증한다.</li>
 * <li>시설 ID·시설 대장·관할 부서·사건 상태를 바꾸지 않고 알림도 만들지 않는다. 정정 기록과 감사는 같은 트랜잭션이다.</li>
 * <li>잠금(DB 설계 12절): 그 사진이 연결된 열린 검토 작업(4) → 사진 시도(11) → 정정 행 추가(12). 판정과 같은 순서로 직렬화한다.</li>
 * </ul>
 */
@Service
public class ClassificationService {

    public record Classification(String source, String facilityType, List<String> issueTypes) {
    }

    public record ClassificationResult(UUID correctionId, UUID inspectionId, UUID analysisResultId, Classification previous,
            Classification current, String reason, int classificationVersion, Actor correctedBy, Instant correctedAt) {
    }

    public record HistoryItem(UUID correctionId, UUID analysisResultId, Classification previous, Classification current, String reason,
            int classificationVersion, Actor correctedBy, Instant correctedAt) {
    }

    private static final Set<String> FACILITY_TYPES = Set.of("SIDEWALK", "DRAIN", "BENCH", "SAFETY_FENCE", "EXERCISE_EQUIPMENT",
            "PARK_EQUIPMENT", "SNOW_REMOVAL_BIN", "SHADE_STRUCTURE");

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final ReviewReader reader;
    private final DefectDictionary dictionary;
    private final IdempotencyService idempotency;
    private final AuditLog audit;
    private final JsonMapper json;
    private final Clock clock;

    public ClassificationService(JdbcTemplate jdbc, AccessPolicy access, ReviewReader reader, DefectDictionary dictionary,
            IdempotencyService idempotency, AuditLog audit, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.reader = reader;
        this.dictionary = dictionary;
        this.idempotency = idempotency;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyService.Outcome<ClassificationResult> correct(AuthenticatedUser user, UUID inspectionId, UUID key, UUID analysisResultId,
            String facilityType, List<String> issueTypes, String reason, Integer classificationVersion) {
        UUID program = requireInspection(user, inspectionId);
        List<FieldError> errors = new ArrayList<>();
        if (analysisResultId == null) {
            errors.add(new FieldError("analysisResultId", "REQUIRED", "판독 결과 ID가 필요합니다."));
        }
        if (facilityType == null || !FACILITY_TYPES.contains(facilityType)) {
            errors.add(new FieldError("facilityType", facilityType == null ? "REQUIRED" : "INVALID_FORMAT", "시설 종류를 확인해 주세요."));
        }
        if (issueTypes == null) {
            errors.add(new FieldError("issueTypes", "REQUIRED", "문제 유형 목록이 필요합니다(없으면 빈 목록)."));
        } else if (facilityType != null && FACILITY_TYPES.contains(facilityType)) {
            if (new LinkedHashSet<>(issueTypes).size() != issueTypes.size()
                    || issueTypes.stream().anyMatch(c -> c == null || !dictionary.allows(facilityType, c))) {
                errors.add(new FieldError("issueTypes", "INVALID_FORMAT", "이 시설 종류의 문제 유형이 아니거나 중복입니다."));
            }
        }
        if (reason == null || reason.isBlank()) {
            errors.add(new FieldError("reason", "REQUIRED", "정정 사유가 필요합니다."));
        } else if (reason.length() > 500) {
            errors.add(new FieldError("reason", "TOO_LONG", "500자 이하여야 합니다."));
        }
        if (classificationVersion == null || classificationVersion < 0) {
            errors.add(new FieldError("classificationVersion", classificationVersion == null ? "REQUIRED" : "OUT_OF_RANGE", "분류 버전이 필요합니다."));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, errors);
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("inspectionId", inspectionId);
        identity.put("analysisResultId", analysisResultId);
        identity.put("facilityType", facilityType);
        identity.put("issueTypes", issueTypes);
        identity.put("reason", reason);
        identity.put("classificationVersion", classificationVersion);
        return idempotency.execute(user.userId(), "POST /admin/inspections/{inspectionId}/classification-corrections", key, identity,
                ClassificationResult.class, 201,
                () -> apply(user, program, inspectionId, analysisResultId, facilityType, issueTypes, reason.strip(), classificationVersion));
    }

    private ClassificationResult apply(AuthenticatedUser user, UUID program, UUID inspectionId, UUID analysisResultId, String facilityType,
            List<String> issueTypes, String reason, int classificationVersion) {
        // 잠금: 시설(1, 공유) → 열린 검토 작업(4) → 사진 시도(11). 시설 잠금 뒤 현재 관할을 다시 확인한다(관할 이관 경합, REVIEW-05).
        UUID facility = facilityOf(inspectionId);
        jdbc.queryForList("SELECT id FROM facilities WHERE id = ? FOR SHARE", facility);
        access.requireFacility(user, facility);
        jdbc.queryForList("""
                SELECT t.id FROM review_tasks t JOIN review_task_inspections x ON x.review_task_id = t.id
                WHERE x.inspection_id = ? AND t.status <> 'RESOLVED' ORDER BY t.id FOR UPDATE OF t""", inspectionId);
        jdbc.queryForList("SELECT id FROM inspections WHERE id = ? FOR UPDATE", inspectionId);
        InspectionRow i = reader.inspection(inspectionId).orElseThrow();
        String status = jdbc.query("""
                SELECT r.result_json->>'status' FROM analysis_results r JOIN analysis_jobs j ON j.id = r.analysis_job_id
                WHERE r.id = ? AND j.inspection_id = ? AND r.program_id = ?""", (rs, n) -> rs.getString(1), analysisResultId, inspectionId, program)
                .stream().findFirst().orElse(null);
        if (!"ok".equals(status) && !"needs_review".equals(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, List.of(new FieldError("analysisResultId",
                    "INVALID_FORMAT", "이 사진의 판독 결과(분류 대상)가 아닙니다.")));
        }
        if (i.classificationVersion() != classificationVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "사진 분류가 변경되었습니다. 최신 내용을 확인해 주세요.",
                    Map.of("currentVersion", i.classificationVersion()));
        }
        InspectionClassification before = reader.classification(i, reader.snapshots(inspectionId));
        Classification previous = new Classification(before.current().source(), before.current().facilityType(), before.current().issueTypes());
        int next = classificationVersion + 1;
        Instant now = clock.instant();
        UUID id = jdbc.queryForObject("""
                INSERT INTO classification_corrections (program_id, inspection_id, analysis_result_id, previous_facility_type, previous_issue_types,
                    new_facility_type, new_issue_types, reason, actor_user_id, classification_version, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?) RETURNING id""", UUID.class,
                program, inspectionId, analysisResultId, previous.facilityType(), json.writeValueAsString(previous.issueTypes()), facilityType,
                json.writeValueAsString(issueTypes), reason, user.userId(), next, Timestamp.from(now));
        jdbc.update("UPDATE inspections SET classification_version = ?, updated_at = ? WHERE id = ?", next, Timestamp.from(now), inspectionId);
        Classification current = new Classification("HUMAN", facilityType, List.copyOf(issueTypes));
        audit.record(program, user.userId(), "CLASSIFICATION_CORRECTED", "INSPECTION", inspectionId, reason,
                Map.of("facilityType", previous.facilityType(), "issueTypes", previous.issueTypes(), "classificationVersion", classificationVersion),
                Map.of("facilityType", facilityType, "issueTypes", issueTypes, "classificationVersion", next, "correctionId", id));
        return new ClassificationResult(id, inspectionId, analysisResultId, previous, current, reason, next, actor(user.userId()), now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<HistoryItem> history(AuthenticatedUser user, UUID inspectionId, Integer page, Integer size) {
        requireInspection(user, inspectionId);
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.max(1, Math.min(100, size));
        List<HistoryItem> items = jdbc.query("""
                SELECT c.id, c.analysis_result_id, c.previous_facility_type, c.previous_issue_types::text, c.new_facility_type,
                    c.new_issue_types::text, c.reason, c.classification_version, c.actor_user_id, u.name, c.created_at
                FROM classification_corrections c JOIN users u ON u.id = c.actor_user_id
                WHERE c.inspection_id = ? ORDER BY c.classification_version LIMIT ? OFFSET ?""",
                (rs, n) -> new HistoryItem(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        new Classification(rs.getInt(8) == 1 ? "AI" : "HUMAN", rs.getString(3), reader.codes(rs.getString(4))),
                        new Classification("HUMAN", rs.getString(5), reader.codes(rs.getString(6))), rs.getString(7), rs.getInt(8),
                        new Actor(rs.getObject(9, UUID.class), rs.getString(10)), ReviewReader.ts(rs.getObject(11, OffsetDateTime.class))),
                inspectionId, s, (long) p * s);
        Long total = jdbc.queryForObject("SELECT count(*) FROM classification_corrections WHERE inspection_id = ?", Long.class, inspectionId);
        return new Page<>(items, p, s, total == null ? 0 : total);
    }

    /** 시설 담당자·사진 시설의 현재 관할. 사업 ID. */
    private UUID requireInspection(AuthenticatedUser user, UUID inspectionId) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        return access.requireFacility(user, facilityOf(inspectionId));
    }

    private UUID facilityOf(UUID inspectionId) {
        return jdbc.query("""
                SELECT ps.facility_id FROM inspections i JOIN capture_slots s ON s.id = i.capture_slot_id JOIN patrol_stops ps ON ps.id = s.patrol_stop_id
                WHERE i.id = ?""", (rs, n) -> rs.getObject(1, UUID.class), inspectionId).stream().findFirst()
                .orElseThrow(ReviewQueryService::notFound);
    }

    private Actor actor(UUID userId) {
        return new Actor(userId, jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, userId));
    }
}
