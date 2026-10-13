package kr.iroum.inspection.review;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse.FieldError;
import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.review.ReviewViews.Actor;
import kr.iroum.inspection.review.ReviewViews.IssueResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검토 작업 사람 판정(업무 API 9.1절, D-08·D-13·D-25·D-26).
 * <ul>
 * <li>decision: ISSUE_CONFIRMED·NO_ISSUE·FOLLOW_UP_REQUIRED(followUpType 필수)·RESOLVE_CAPTURE_PROBLEM. 반복 촬영 실패(CAPTURE_FAILURE)는 추가 확인·촬영 장애
 * 처리 종료만, 사진 작업은 촬영 장애 처리 종료 불가. 이상 확인·이상 없음은 작업 사진의 현재 사본이 개인정보 승인 상태여야 한다(재승인 포함,
 * 구도 채택과 별개, REVIEW-06).</li>
 * <li>판정 근거 3키(analysisResultId, correctionId, classificationVersion)는 같은 작업·사진·사업의 스냅샷·정정이어야 하고, 현재 사람 분류 버전과
 * 다르면 409 VERSION_CONFLICT. 작업 version이 다르면 409 VERSION_CONFLICT(동시 판정은 하나만 성공).</li>
 * <li>issueResolutions: 문제마다 신규(CREATE) 또는 기존 열린 문제 연결(LINK)을 담당자가 명시한다. 시설+코드로 자동 병합하지 않고 AI duplicate_of를 쓰지 않는다.
 * LINK 대상은 같은 사업·시설·현재 관할의 미종결 문제(종결이면 409 ISSUE_NOT_LINKABLE, 범위 밖 404)이며 기존 문제의 상태·설명·보수 회차를 바꾸지 않는다.</li>
 * <li>판정·신규 문제·증거 연결·재점검 요청(추가 확인)·작업 완료·감사·outbox·멱등 결과가 한 트랜잭션이다. 하나라도 실패하면 모두 롤백한다.</li>
 * <li>잠금(DB 설계 12절): 시설(1) → LINK 대상 문제(2, ID순) → 검토 작업(4) → 사진 시도(11) → 판정 행 추가(12). 시설 잠금을 얻은 뒤 현재 관할을
 * 다시 확인한다(관할 이관과 경합하면 이관 뒤 이전 부서 담당자는 404, REVIEW-05).</li>
 * </ul>
 */
@Service
public class DecisionService {

    public record Resolution(String mode, UUID inspectionId, UUID existingIssueId, String defectCode, String summary) {
    }

    public record DecisionResult(UUID reviewId, UUID reviewTaskId, String reviewTaskStatus, String decision, String followUpType,
            UUID reinspectionRequestId, Actor decidedBy, Instant decidedAt, long version, UUID analysisResultId, UUID correctionId,
            Integer classificationVersion, List<UUID> issueIds, List<IssueResult> issueResults) {
    }

    private static final Set<String> DECISIONS = Set.of("ISSUE_CONFIRMED", "NO_ISSUE", "FOLLOW_UP_REQUIRED", "RESOLVE_CAPTURE_PROBLEM");

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final DefectDictionary dictionary;
    private final IdempotencyService idempotency;
    private final AuditLog audit;
    private final JsonMapper json;
    private final Clock clock;

    public DecisionService(JdbcTemplate jdbc, AccessPolicy access, DefectDictionary dictionary, IdempotencyService idempotency, AuditLog audit,
            JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.dictionary = dictionary;
        this.idempotency = idempotency;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyService.Outcome<DecisionResult> decide(AuthenticatedUser user, UUID taskId, UUID key, String decision, String followUpType,
            UUID analysisResultId, UUID correctionId, Integer classificationVersion, String reason, Long version, List<Resolution> resolutions) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        Map<String, Object> task = jdbc.queryForList("SELECT id, type, facility_id, patrol_stop_id FROM review_tasks WHERE id = ?", taskId).stream()
                .findFirst().orElseThrow(ReviewQueryService::notFound);
        UUID program = access.requireFacility(user, (UUID) task.get("facility_id"));
        validate((String) task.get("type"), decision, followUpType, analysisResultId, correctionId, classificationVersion, reason, version,
                resolutions);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("taskId", taskId);
        identity.put("decision", decision);
        identity.put("followUpType", followUpType);
        identity.put("analysisResultId", analysisResultId);
        identity.put("correctionId", correctionId);
        identity.put("classificationVersion", classificationVersion);
        identity.put("reason", reason);
        identity.put("version", version);
        identity.put("issueResolutions", resolutions);
        return idempotency.execute(user.userId(), "POST /admin/review-tasks/{reviewTaskId}/decisions", key, identity, DecisionResult.class, 201,
                () -> apply(user, program, taskId, (UUID) task.get("facility_id"), decision, followUpType, analysisResultId, correctionId,
                        classificationVersion, reason.strip(), version, resolutions));
    }

    private void validate(String taskType, String decision, String followUpType, UUID analysisResultId, UUID correctionId,
            Integer classificationVersion, String reason, Long version, List<Resolution> resolutions) {
        List<FieldError> e = new ArrayList<>();
        if (decision == null || !DECISIONS.contains(decision)) {
            e.add(new FieldError("decision", decision == null ? "REQUIRED" : "INVALID_FORMAT", "판정 값을 확인해 주세요."));
        } else {
            boolean captureFailure = "CAPTURE_FAILURE".equals(taskType);
            boolean allowed = captureFailure ? List.of("FOLLOW_UP_REQUIRED", "RESOLVE_CAPTURE_PROBLEM").contains(decision)
                    : "INSPECTION".equals(taskType) && !"RESOLVE_CAPTURE_PROBLEM".equals(decision);
            if (!allowed) {
                e.add(new FieldError("decision", "INVALID_FORMAT", "이 검토 작업에 쓸 수 없는 판정입니다."));
            }
            boolean followUp = "FOLLOW_UP_REQUIRED".equals(decision);
            if (followUp != (followUpType != null) || (followUpType != null && !List.of("REPHOTO", "SITE_VISIT").contains(followUpType))) {
                e.add(new FieldError("followUpType", followUp ? "REQUIRED" : "INVALID_FORMAT", "추가 확인일 때만 REPHOTO·SITE_VISIT를 보냅니다."));
            }
            if (resolutions == null) {
                e.add(new FieldError("issueResolutions", "REQUIRED", "문제 처리 목록이 필요합니다(없으면 빈 목록)."));
            } else if ("ISSUE_CONFIRMED".equals(decision) == resolutions.isEmpty()) {
                e.add(new FieldError("issueResolutions", "INVALID_FORMAT", "이상 확인은 문제 1건 이상, 다른 판정은 빈 목록입니다."));
            }
        }
        if (analysisResultId == null) {
            if (correctionId != null || classificationVersion != null) {
                e.add(new FieldError("classificationVersion", "INVALID_FORMAT", "AI 결과 없이 판단하면 정정 ID·분류 버전도 null입니다."));
            }
        } else if (classificationVersion == null || classificationVersion < 0) {
            e.add(new FieldError("classificationVersion", "REQUIRED", "AI 결과로 판단하면 분류 버전이 필요합니다."));
        } else if ((classificationVersion == 0) != (correctionId == null)) {
            e.add(new FieldError("correctionId", "INVALID_FORMAT", "분류 버전 0은 정정 ID 없음, 1 이상은 그 정정 ID가 필요합니다."));
        }
        if (reason == null || reason.isBlank()) {
            e.add(new FieldError("reason", "REQUIRED", "판정 이유가 필요합니다."));
        } else if (reason.length() > 500) {
            e.add(new FieldError("reason", "TOO_LONG", "500자 이하여야 합니다."));
        }
        if (version == null || version < 0) {
            e.add(new FieldError("version", "REQUIRED", "검토 작업 version이 필요합니다."));
        }
        if (resolutions != null) {
            Set<UUID> photos = new HashSet<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < resolutions.size(); i++) {
                Resolution r = resolutions.get(i);
                String f = "issueResolutions[" + i + "]";
                if (r == null || r.inspectionId() == null) {
                    e.add(new FieldError(f + ".inspectionId", "REQUIRED", "사진 ID가 필요합니다."));
                    continue;
                }
                photos.add(r.inspectionId());
                if ("CREATE".equals(r.mode())) {
                    if (r.existingIssueId() != null || r.defectCode() == null || r.defectCode().isBlank() || r.summary() == null
                            || r.summary().isBlank() || r.summary().length() > 500) {
                        e.add(new FieldError(f, "INVALID_FORMAT", "신규 등록은 기존 문제 없이 문제 코드와 1~500자 설명이 필요합니다."));
                    }
                } else if ("LINK".equals(r.mode())) {
                    if (r.existingIssueId() == null || r.defectCode() != null || r.summary() != null) {
                        e.add(new FieldError(f, "INVALID_FORMAT", "기존 연결은 기존 문제 ID만 보내고 코드·설명은 null입니다."));
                    } else if (!seen.add(r.inspectionId() + "/" + r.existingIssueId())) {
                        e.add(new FieldError(f + ".existingIssueId", "INVALID_FORMAT", "같은 사진·같은 기존 문제를 두 번 지정할 수 없습니다."));
                    }
                } else {
                    e.add(new FieldError(f + ".mode", r.mode() == null ? "REQUIRED" : "INVALID_FORMAT", "CREATE 또는 LINK입니다."));
                }
            }
            if (photos.size() > 1) {
                e.add(new FieldError("issueResolutions", "INVALID_FORMAT", "한 판정의 처분 대상 사진은 하나입니다."));
            }
        }
        if (!e.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, e);
        }
    }

    private DecisionResult apply(AuthenticatedUser user, UUID program, UUID taskId, UUID facilityId, String decision, String followUpType,
            UUID analysisResultId, UUID correctionId, Integer classificationVersion, String reason, long version, List<Resolution> resolutions) {
        // 잠금: 시설 → LINK 대상 문제(ID순) → 검토 작업 → 사진 시도
        jdbc.queryForList("SELECT id FROM facilities WHERE id = ? FOR UPDATE", facilityId);
        // 잠금 대기 중 관할이 바뀌었을 수 있다(DB 설계 12절: 잠금 뒤 최신 값 재확인). 같은 트랜잭션에서 현재 관할을 다시 본다(범위 밖 404).
        access.requireFacility(user, facilityId);
        TreeSet<UUID> links = new TreeSet<>();
        resolutions.stream().filter(r -> "LINK".equals(r.mode())).forEach(r -> links.add(r.existingIssueId()));
        for (UUID issue : links) {
            jdbc.queryForList("SELECT id FROM issues WHERE id = ? FOR UPDATE", issue);
        }
        Map<String, Object> task = jdbc.queryForMap("SELECT type, status, version, facility_id FROM review_tasks WHERE id = ? FOR UPDATE", taskId);
        List<UUID> photos = jdbc.queryForList("""
                SELECT x.inspection_id FROM review_task_inspections x WHERE x.review_task_id = ? ORDER BY x.inspection_id""", UUID.class, taskId);
        for (UUID p : photos) {
            jdbc.queryForList("SELECT id FROM inspections WHERE id = ? FOR UPDATE", p);
        }
        long current = ((Number) task.get("version")).longValue();
        if (current != version || "RESOLVED".equals(task.get("status"))) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 판정했거나 작업이 바뀌었습니다. 최신 내용을 확인해 주세요.",
                    Map.of("currentVersion", current));
        }
        // 판정할 수 있는 사진: 현재 사본이 개인정보 승인된 시도(재승인 포함). 구도 채택 여부와 별개이며 채택·활동 상태를 바꾸지 않는다(REVIEW-06).
        List<UUID> approved = jdbc.queryForList(ReviewReader.REVIEWABLE_INSPECTIONS, UUID.class, taskId);
        if (List.of("ISSUE_CONFIRMED", "NO_ISSUE").contains(decision) && approved.isEmpty()) {
            throw invalid("decision", "판정할 승인 사진이 없습니다(보류 등). 추가 확인으로 처리해 주세요.");
        }
        UUID analysisInspection = null;
        if (analysisResultId != null) {
            analysisInspection = jdbc.query("""
                    SELECT j.inspection_id FROM analysis_results r JOIN analysis_jobs j ON j.id = r.analysis_job_id
                    WHERE r.id = ? AND r.program_id = ?""", (rs, n) -> rs.getObject(1, UUID.class), analysisResultId, program).stream()
                    .findFirst().filter(photos::contains).orElse(null);
            if (analysisInspection == null) {
                throw invalid("analysisResultId", "이 검토 작업 사진의 AI 결과가 아닙니다.");
            }
            int currentVersion = jdbc.queryForObject("SELECT classification_version FROM inspections WHERE id = ?", Integer.class,
                    analysisInspection);
            UUID currentCorrection = currentVersion == 0 ? null : jdbc.queryForObject("""
                    SELECT id FROM classification_corrections WHERE inspection_id = ? AND classification_version = ?""", UUID.class,
                    analysisInspection, currentVersion);
            if (currentVersion != classificationVersion || (currentCorrection == null ? correctionId != null : !currentCorrection.equals(correctionId))) {
                throw new ApiException(ErrorCode.VERSION_CONFLICT, "사진 분류가 변경되었습니다. 최신 내용을 확인해 주세요.",
                        Map.of("currentVersion", currentVersion));
            }
        }
        String ledgerType = jdbc.queryForObject("SELECT type FROM facilities WHERE id = ?", String.class, facilityId);
        for (int i = 0; i < resolutions.size(); i++) {
            Resolution r = resolutions.get(i);
            if (!approved.contains(r.inspectionId()) || (analysisInspection != null && !analysisInspection.equals(r.inspectionId()))) {
                throw invalid("issueResolutions[" + i + "].inspectionId", "작업의 현재 승인 사진(선택한 AI 결과의 사진)이 아닙니다.");
            }
            if ("CREATE".equals(r.mode()) && !dictionary.allows(ledgerType, r.defectCode())) {
                throw invalid("issueResolutions[" + i + "].defectCode", "이 시설 종류의 문제 코드가 아닙니다.");
            }
        }
        Map<UUID, Map<String, Object>> linked = new LinkedHashMap<>();
        for (UUID issue : links) {
            Map<String, Object> row = jdbc.queryForList("SELECT id, program_id, facility_id, status, defect_code, summary FROM issues WHERE id = ?",
                    issue).stream().findFirst().filter(m -> program.equals(m.get("program_id")) && facilityId.equals(m.get("facility_id")))
                    .orElseThrow(ReviewQueryService::notFound);
            if ("CLOSED".equals(row.get("status"))) {
                throw new ApiException(ErrorCode.ISSUE_NOT_LINKABLE, "선택한 문제는 이미 종결되어 연결할 수 없습니다.",
                        Map.of("issueId", issue, "currentStatus", "CLOSED"));
            }
            linked.put(issue, row);
        }
        Instant now = clock.instant();
        UUID reviewId = jdbc.queryForObject("""
                INSERT INTO review_decisions (program_id, review_task_id, analysis_result_id, classification_correction_id, classification_version,
                    decided_by, decision, follow_up_type, reason, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
                program, taskId, analysisResultId, correctionId, classificationVersion, user.userId(), decision, followUpType, reason,
                Timestamp.from(now));
        UUID department = jdbc.queryForObject("SELECT managing_department_id FROM facilities WHERE id = ?", UUID.class, facilityId);
        List<IssueResult> results = new ArrayList<>();
        int seq = 0;
        for (Resolution r : resolutions) {
            UUID issueId;
            String code;
            String summary;
            if ("CREATE".equals(r.mode())) {
                code = r.defectCode();
                summary = r.summary().strip();
                issueId = jdbc.queryForObject("""
                        INSERT INTO issues (program_id, facility_id, defect_code, summary, status, assigned_department_id, source_review_decision_id,
                            opened_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
                        program, facilityId, code, summary, department, reviewId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            } else {
                Map<String, Object> row = linked.get(r.existingIssueId());
                issueId = r.existingIssueId();
                code = (String) row.get("defect_code");
                summary = (String) row.get("summary");
            }
            Boolean active = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM issue_inspections WHERE issue_id = ? AND inspection_id = ? AND unlinked_at IS NULL)""",
                    Boolean.class, issueId, r.inspectionId());
            if (!Boolean.TRUE.equals(active)) {
                jdbc.update("""
                        INSERT INTO issue_inspections (program_id, issue_id, inspection_id, linked_by, linked_at, reason) VALUES (?, ?, ?, ?, ?, ?)""",
                        program, issueId, r.inspectionId(), user.userId(), Timestamp.from(now), reason);
            }
            jdbc.update("""
                    INSERT INTO review_decision_issues (program_id, review_decision_id, sequence_no, inspection_id, issue_id, mode, defect_code_snapshot,
                        summary_snapshot, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    program, reviewId, ++seq, r.inspectionId(), issueId, r.mode(), code, summary, Timestamp.from(now));
            results.add(new IssueResult(r.mode(), r.inspectionId(), issueId, code));
        }
        UUID reinspection = null;
        if ("FOLLOW_UP_REQUIRED".equals(decision)) {
            // 추가 확인: 다음 정기 순회에 반영할 재점검 요청(배정·실행은 10절 후속 범위)
            reinspection = jdbc.queryForObject("""
                    INSERT INTO reinspection_requests (program_id, facility_id, review_decision_id, request_type, status, requested_by, created_at,
                        updated_at) VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?) RETURNING id""", UUID.class,
                    program, facilityId, reviewId, followUpType, user.userId(), Timestamp.from(now), Timestamp.from(now));
        }
        long next = jdbc.queryForObject("""
                UPDATE review_tasks SET status = 'RESOLVED', version = version + 1, updated_at = ? WHERE id = ? RETURNING version""", Long.class,
                Timestamp.from(now), taskId);
        List<UUID> issueIds = ReviewQueryService.issueIds(results);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reviewId", reviewId);
        after.put("decision", decision);
        after.put("followUpType", followUpType);
        after.put("analysisResultId", analysisResultId);
        after.put("correctionId", correctionId);
        after.put("classificationVersion", classificationVersion);
        after.put("issueIds", issueIds);
        after.put("reinspectionRequestId", reinspection);
        audit.record(program, user.userId(), "REVIEW_DECIDED", "REVIEW_TASK", taskId, reason, Map.of("status", "OPEN", "version", version), after);
        jdbc.update("INSERT INTO outbox_events (event_type, aggregate_id, payload_json, next_run_at) VALUES ('REVIEW_DECIDED', ?, ?::jsonb, ?)",
                taskId, json.writeValueAsString(after), Timestamp.from(now));
        String name = jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, user.userId());
        return new DecisionResult(reviewId, taskId, "RESOLVED", decision, followUpType, reinspection, new Actor(user.userId(), name), now, next,
                analysisResultId, correctionId, classificationVersion, issueIds, results);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, List.of(new FieldError(field, "INVALID_FORMAT", message)));
    }
}
