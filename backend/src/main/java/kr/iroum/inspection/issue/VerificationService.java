package kr.iroum.inspection.issue;

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
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse.FieldError;
import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.review.ReviewReader;
import kr.iroum.inspection.review.ReviewViews.Actor;
import kr.iroum.inspection.review.ReviewViews.Media;

/**
 * 수리 확인 준비·판정·이력(업무 API 10절, DB 설계 9절, VERIFY-01). 시설 담당자(현재 관할)만 하며 최종 판단은 사람이 한다.
 * <ul>
 * <li>준비: 현재 재점검 요청·그 요청의 보수 완료 이벤트에 대해 verification을 새로 만든다(증거가 바뀌면 새 준비). PHOTO는 이 문제에 연결된 같은 시설
 * 사진(BEFORE)과 현재 요청에 배정된 구도에서 채택된 사진(AFTER)이 필요하고, 모두 현재 사본이 개인정보 승인 상태여야 한다. SITE_VISIT는 현장 확인
 * 내용(fieldNote)이 필수다.</li>
 * <li>AI 비교(/verify)는 호출하지 않는다(T12, 수리 전 사본 ID 저장 설계가 착수 조건). comparisonStatus는 null이며 사람 확인은 그대로 가능하다(10절).</li>
 * <li>판정: CLOSE(종결) / REPAIR_AGAIN(재보수, 요청 종료 + REPAIR_REQUESTED + RE_REQUESTED 이력) / FOLLOW_UP_REQUIRED(같은 요청 유지, 확인 방법 갱신).
 * 준비 때 검토한 증거 사본(media ID·사진 버전)을 고정하고(V20), 판정 때 현재 사본이 다르면 409 STALE_VERIFICATION(새 준비 필요, VERIFY-03).
 * 지난 회차(현재 요청이 아님·끝난 요청·다른 보수 이벤트)와 대체된 준비(준비 뒤 요청 version이 바뀜)는 409 STALE_VERIFICATION,
 * version이 다르면 409 VERSION_CONFLICT(which).</li>
 * <li>잠금: 시설(1, 공유, 관할 재확인) → 문제(2) → 요청(3) → verification(5) → 사진 시도(11) → media(13, 판정 때 증거 승인 상태 재확인).</li>
 * </ul>
 */
@Service
public class VerificationService {

    private static final Set<String> DECISIONS = Set.of("CLOSE", "REPAIR_AGAIN", "FOLLOW_UP_REQUIRED");

    public record PreparationResult(UUID verificationId, long verificationVersion, String comparisonStatus, UUID requestId, long requestVersion,
            UUID issueId, long issueVersion) {
    }

    public record DecisionResult(UUID verificationId, String decision, String followUpType, String reason, Actor decidedBy, Instant decidedAt,
            long verificationVersion, UUID issueId, String issueStatus, long issueVersion, UUID requestId, String requestStatus, long requestVersion) {
    }

    public record Evidence(UUID inspectionId, Instant capturedAt, Media media) {
    }

    public record Note(Actor author, String note, Instant createdAt) {
    }

    public record Verification(UUID id, UUID requestId, UUID repairEventId, String verificationMethod, long version, String comparisonStatus,
            List<Evidence> before, List<Evidence> after, List<Note> notes, String decision, String followUpType, String reason, Actor decidedBy,
            Instant decidedAt, Instant createdAt) {
    }

    public record Verifications(List<Verification> items, int page, int size, long totalElements) {
    }

    private final JdbcTemplate jdbc;
    private final IssueLocks locks;
    private final IssueQueryService issues;
    private final ReviewReader reader;
    private final IdempotencyService idempotency;
    private final AuditLog audit;
    private final Clock clock;

    public VerificationService(JdbcTemplate jdbc, IssueLocks locks, IssueQueryService issues, ReviewReader reader, IdempotencyService idempotency,
            AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.issues = issues;
        this.reader = reader;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyService.Outcome<PreparationResult> prepare(AuthenticatedUser user, UUID issueId, UUID key, UUID requestId, UUID repairEventId,
            String method, List<UUID> beforeIds, List<UUID> afterIds, String fieldNote, Long version, Long requestVersion) {
        locks.requireScope(user, issueId);
        List<FieldError> e = new ArrayList<>();
        if (requestId == null) {
            e.add(new FieldError("requestId", "REQUIRED", "재점검 요청 ID가 필요합니다."));
        }
        if (repairEventId == null) {
            e.add(new FieldError("repairEventId", "REQUIRED", "보수 완료 이력 ID가 필요합니다."));
        }
        boolean photo = "PHOTO".equals(method);
        if (!photo && !"SITE_VISIT".equals(method)) {
            e.add(new FieldError("verificationMethod", method == null ? "REQUIRED" : "INVALID_FORMAT", "PHOTO 또는 SITE_VISIT입니다."));
        }
        idList(e, "beforeInspectionIds", beforeIds, photo);
        idList(e, "afterInspectionIds", afterIds, photo);
        if ("SITE_VISIT".equals(method) && (fieldNote == null || fieldNote.isBlank())) {
            e.add(new FieldError("fieldNote", "REQUIRED", "현장 확인은 확인 내용이 필요합니다."));
        } else if (fieldNote != null && fieldNote.length() > 2000) {
            e.add(new FieldError("fieldNote", "TOO_LONG", "2000자 이하여야 합니다."));
        }
        versionField(e, "version", version);
        versionField(e, "requestVersion", requestVersion);
        fail(e);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("issueId", issueId);
        identity.put("requestId", requestId);
        identity.put("repairEventId", repairEventId);
        identity.put("verificationMethod", method);
        identity.put("beforeInspectionIds", beforeIds);
        identity.put("afterInspectionIds", afterIds);
        identity.put("fieldNote", fieldNote);
        identity.put("version", version);
        identity.put("requestVersion", requestVersion);
        return idempotency.execute(user.userId(), "POST /admin/issues/{issueId}/verification-preparations", key, identity, PreparationResult.class,
                201, () -> {
                    Map<String, Object> issue = locks.lock(user, issueId);
                    Map<String, Object> request = currentRequest(issue, requestId);
                    if (!repairEventId.equals(request.get("repair_event_id"))) {
                        throw stale(issue, "다른 보수 회차의 이력입니다. 최신 내용을 확인해 주세요.");
                    }
                    requireVersion(issue, version, "issue");
                    requireVersion(request, requestVersion, "request");
                    IssueLocks.requireStatus(issue, "AWAITING_VERIFICATION");
                    UUID program = (UUID) issue.get("program_id");
                    lockInspections(beforeIds, afterIds);
                    List<FieldError> evidence = new ArrayList<>();
                    for (int i = 0; i < beforeIds.size(); i++) {
                        if (!validBefore(issueId, (UUID) issue.get("facility_id"), beforeIds.get(i))) {
                            evidence.add(new FieldError("beforeInspectionIds[" + i + "]", "INVALID_FORMAT",
                                    "이 문제에 연결된 같은 시설의 승인 사진이 아닙니다."));
                        }
                    }
                    for (int i = 0; i < afterIds.size(); i++) {
                        if (!validAfter(requestId, afterIds.get(i))) {
                            evidence.add(new FieldError("afterInspectionIds[" + i + "]", "INVALID_FORMAT",
                                    "현재 재점검 요청에 배정된 구도에서 채택된 승인 사진이 아닙니다."));
                        }
                    }
                    fail(evidence);
                    Instant now = clock.instant();
                    // 준비가 바뀌면 이전 준비로 판정하지 못하도록 요청 version을 올리고, 이 준비가 유효한 요청 version으로 기록한다(VERIFY-02).
                    long nextRequest = bumpRequest(requestId, null, null, now);
                    UUID verification = jdbc.queryForObject("""
                            INSERT INTO verifications (program_id, request_id, verification_method, prepared_request_version, created_at)
                            VALUES (?, ?, ?, ?, ?) RETURNING id""", UUID.class, program, requestId, method, nextRequest, Timestamp.from(now));
                    // 준비 때 검토한 사본(media ID·사진 버전)을 고정한다(VERIFY-03). 위에서 모두 승인 상태임을 확인했다.
                    for (UUID id : new LinkedHashSet<>(beforeIds)) {
                        insertEvidence(program, verification, id, "BEFORE");
                    }
                    for (UUID id : new LinkedHashSet<>(afterIds)) {
                        insertEvidence(program, verification, id, "AFTER");
                    }
                    if (fieldNote != null && !fieldNote.isBlank()) {
                        jdbc.update("INSERT INTO verification_notes (program_id, verification_id, author_user_id, note, created_at) VALUES (?, ?, ?, ?, ?)",
                                program, verification, user.userId(), fieldNote.strip(), Timestamp.from(now));
                    }
                    audit.record(program, user.userId(), "VERIFICATION_PREPARED", "ISSUE", issueId, null, Map.of("requestId", requestId),
                            Map.of("verificationId", verification, "verificationMethod", method, "beforeInspectionIds", beforeIds,
                                    "afterInspectionIds", afterIds));
                    return new PreparationResult(verification, 0, null, requestId, nextRequest, issueId, ((Number) issue.get("version")).longValue());
                });
    }

    @Transactional
    public IdempotencyService.Outcome<DecisionResult> decide(AuthenticatedUser user, UUID issueId, UUID key, UUID verificationId, String decision,
            String followUpType, String reason, Long version, Long requestVersion, Long verificationVersion) {
        locks.requireScope(user, issueId);
        List<FieldError> e = new ArrayList<>();
        if (verificationId == null) {
            e.add(new FieldError("verificationId", "REQUIRED", "수리 확인 준비 ID가 필요합니다."));
        }
        if (decision == null || !DECISIONS.contains(decision)) {
            e.add(new FieldError("decision", decision == null ? "REQUIRED" : "INVALID_FORMAT", "CLOSE·REPAIR_AGAIN·FOLLOW_UP_REQUIRED입니다."));
        }
        boolean followUp = "FOLLOW_UP_REQUIRED".equals(decision);
        if (followUp != (followUpType != null) || (followUpType != null && !List.of("REPHOTO", "SITE_VISIT").contains(followUpType))) {
            e.add(new FieldError("followUpType", followUp ? "REQUIRED" : "INVALID_FORMAT", "추가 확인일 때만 REPHOTO·SITE_VISIT를 보냅니다."));
        }
        if (reason == null || reason.isBlank()) {
            e.add(new FieldError("reason", "REQUIRED", "판단 이유가 필요합니다."));
        } else if (reason.length() > 500) {
            e.add(new FieldError("reason", "TOO_LONG", "500자 이하여야 합니다."));
        }
        versionField(e, "version", version);
        versionField(e, "requestVersion", requestVersion);
        versionField(e, "verificationVersion", verificationVersion);
        fail(e);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("issueId", issueId);
        identity.put("verificationId", verificationId);
        identity.put("decision", decision);
        identity.put("followUpType", followUpType);
        identity.put("reason", reason);
        identity.put("version", version);
        identity.put("requestVersion", requestVersion);
        identity.put("verificationVersion", verificationVersion);
        return idempotency.execute(user.userId(), "POST /admin/issues/{issueId}/verification-decisions", key, identity, DecisionResult.class, 201,
                () -> {
                    Map<String, Object> issue = locks.lock(user, issueId);
                    UUID requestId = jdbc.queryForList("""
                            SELECT v.request_id FROM verifications v JOIN reinspection_requests r ON r.id = v.request_id
                            WHERE v.id = ? AND r.issue_id = ?""", UUID.class, verificationId, issueId).stream().findFirst().orElse(null);
                    if (requestId == null) {
                        fail(List.of(new FieldError("verificationId", "INVALID_FORMAT", "이 문제의 수리 확인 준비가 아닙니다.")));
                    }
                    Map<String, Object> request = currentRequest(issue, requestId);
                    Map<String, Object> verification = jdbc.queryForMap("""
                            SELECT id, version, verification_method, decision, prepared_request_version FROM verifications WHERE id = ? FOR UPDATE""",
                            verificationId);
                    requireVersion(issue, version, "issue");
                    requireVersion(request, requestVersion, "request");
                    requireVersion(verification, verificationVersion, "verification");
                    if (verification.get("decision") != null) {
                        throw new ApiException(ErrorCode.VERSION_CONFLICT, "이미 판단한 준비입니다. 최신 내용을 확인해 주세요.",
                                Map.of("currentVersion", ((Number) verification.get("version")).longValue(), "which", "verification"));
                    }
                    IssueLocks.requireStatus(issue, "AWAITING_VERIFICATION");
                    // 현재 유효한 준비인지 서버에서 확인한다: 준비 뒤 요청 version이 바뀌었으면(새 준비·배정 변경·추가 확인) 대체된 준비다.
                    // 클라이언트가 최신 requestVersion을 보내도 대체된 준비는 쓸 수 없다(VERIFY-02).
                    Number prepared = (Number) verification.get("prepared_request_version");
                    if (prepared == null || prepared.longValue() != ((Number) request.get("version")).longValue()) {
                        throw supersededPreparation(issue, requestId, ((Number) request.get("version")).longValue());
                    }
                    // 증거 사진: 준비 뒤 사본이 바뀌었으면(재가림 등) 409 새 준비 필요, 같은 사본이 보류 중이면 400(같은 트랜잭션에서 잠그고 확인).
                    List<Map<String, Object>> evidence = jdbc.queryForList("""
                            SELECT inspection_id, media_id FROM verification_evidence WHERE verification_id = ? ORDER BY inspection_id, role""",
                            verificationId);
                    lockInspections(evidence.stream().map(r -> (UUID) r.get("inspection_id")).toList(), List.of());
                    for (Map<String, Object> row : evidence) {
                        UUID current = currentCopy((UUID) row.get("inspection_id"));
                        if (current == null || !current.equals(row.get("media_id"))) {
                            throw changedEvidence(issue);
                        }
                        if (!approved(current)) {
                            fail(List.of(new FieldError("verificationId", "INVALID_FORMAT", "증거 사진이 보류되었습니다. 다시 준비해 주세요.")));
                        }
                    }
                    UUID program = (UUID) issue.get("program_id");
                    Instant now = clock.instant();
                    String text = reason.strip();
                    long nextVerification = jdbc.queryForObject("""
                            UPDATE verifications SET decided_by = ?, decision = ?, follow_up_type = ?, reason = ?, verified_at = ?, version = version + 1
                            WHERE id = ? RETURNING version""", Long.class, user.userId(), decision, followUpType, text, Timestamp.from(now), verificationId);
                    String issueStatus;
                    String requestStatus;
                    long nextRequest;
                    if ("FOLLOW_UP_REQUIRED".equals(decision)) {
                        // 같은 보수 회차 요청을 열어 두고 확인 방법을 갱신한다. 배정은 활동 담당자가 다시 한다.
                        issueStatus = "AWAITING_VERIFICATION";
                        requestStatus = "OPEN";
                        nextRequest = bumpRequest(requestId, null, followUpType, now);
                        jdbc.update("UPDATE issues SET version = version + 1, updated_at = ? WHERE id = ?", Timestamp.from(now), issueId);
                    } else {
                        requestStatus = "CLOSED";
                        nextRequest = bumpRequest(requestId, "CLOSED", null, now);
                        jdbc.update("""
                                UPDATE reinspection_assignments SET completed_at = ? WHERE request_id = ? AND cancelled_at IS NULL AND completed_at IS NULL""",
                                Timestamp.from(now), requestId);
                        if ("CLOSE".equals(decision)) {
                            issueStatus = "CLOSED";
                            jdbc.update("UPDATE issues SET status = 'CLOSED', closed_at = ?, version = version + 1, updated_at = ? WHERE id = ?",
                                    Timestamp.from(now), Timestamp.from(now), issueId);
                        } else {
                            issueStatus = "REPAIR_REQUESTED";
                            jdbc.update("""
                                    INSERT INTO repair_events (program_id, issue_id, type, actor_user_id, department_id, description, occurred_at, recorded_at)
                                    VALUES (?, ?, 'RE_REQUESTED', ?, ?, ?, ?, ?)""", program, issueId, user.userId(), issue.get("assigned_department_id"),
                                    text, Timestamp.from(now), Timestamp.from(now));
                            jdbc.update("""
                                    UPDATE issues SET status = 'REPAIR_REQUESTED', current_reinspection_request_id = NULL, version = version + 1, updated_at = ?
                                    WHERE id = ?""", Timestamp.from(now), issueId);
                        }
                    }
                    long nextIssue = jdbc.queryForObject("SELECT version FROM issues WHERE id = ?", Long.class, issueId);
                    audit.record(program, user.userId(), "VERIFICATION_DECIDED", "ISSUE", issueId, text,
                            Map.of("status", "AWAITING_VERIFICATION", "requestId", requestId),
                            Map.of("status", issueStatus, "verificationId", verificationId, "decision", decision, "requestStatus", requestStatus));
                    String name = jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, user.userId());
                    return new DecisionResult(verificationId, decision, followUpType, text, new Actor(user.userId(), name), now, nextVerification, issueId,
                            issueStatus, nextIssue, requestId, requestStatus, nextRequest);
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Verifications list(AuthenticatedUser user, UUID issueId, Integer page, Integer size) {
        issues.requireIssue(user, issueId);
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.max(1, Math.min(100, size));
        List<Verification> items = new ArrayList<>();
        for (Map<String, Object> v : jdbc.queryForList("""
                SELECT v.id, v.request_id, r.repair_event_id, v.verification_method, v.version, v.decision, v.follow_up_type, v.reason, v.decided_by,
                    u.name, v.verified_at, v.created_at
                FROM verifications v JOIN reinspection_requests r ON r.id = v.request_id LEFT JOIN users u ON u.id = v.decided_by
                WHERE r.issue_id = ? ORDER BY v.created_at, v.prepared_request_version NULLS FIRST, v.id LIMIT ? OFFSET ?""", issueId, s, (long) p * s)) {
            UUID id = (UUID) v.get("id");
            List<Note> notes = jdbc.query("""
                    SELECT n.author_user_id, u.name, n.note, n.created_at FROM verification_notes n JOIN users u ON u.id = n.author_user_id
                    WHERE n.verification_id = ? ORDER BY n.created_at, n.id""",
                    (rs, n) -> new Note(new Actor(rs.getObject(1, UUID.class), rs.getString(2)), rs.getString(3),
                            ReviewReader.ts(rs.getObject(4, OffsetDateTime.class))), id);
            items.add(new Verification(id, (UUID) v.get("request_id"), (UUID) v.get("repair_event_id"), (String) v.get("verification_method"),
                    ((Number) v.get("version")).longValue(), null, evidence(id, "BEFORE"), evidence(id, "AFTER"), notes, (String) v.get("decision"),
                    (String) v.get("follow_up_type"), (String) v.get("reason"),
                    v.get("decided_by") == null ? null : new Actor((UUID) v.get("decided_by"), (String) v.get("name")),
                    IssueQueryService.instant(v.get("verified_at")), IssueQueryService.instant(v.get("created_at"))));
        }
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM verifications v JOIN reinspection_requests r ON r.id = v.request_id WHERE r.issue_id = ?""", Long.class, issueId);
        return new Verifications(items, p, s, total == null ? 0 : total);
    }

    /** 준비 당시 고정한 사본을 보여 준다(VERIFY-03). V20 이전 행은 고정 사본이 없어 지금 사본을 보여 준다. */
    private List<Evidence> evidence(UUID verificationId, String role) {
        List<Evidence> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT inspection_id, media_id FROM verification_evidence WHERE verification_id = ? AND role = ? ORDER BY inspection_id""",
                verificationId, role)) {
            UUID id = (UUID) r.get("inspection_id");
            UUID media = (UUID) r.get("media_id");
            reader.inspection(id).ifPresent(i -> out.add(new Evidence(id, i.capturedAt(), media == null ? reader.media(i) : reader.media(media))));
        }
        return out;
    }

    private void insertEvidence(UUID program, UUID verification, UUID inspectionId, String role) {
        UUID media = currentCopy(inspectionId);
        Integer revision = jdbc.queryForObject("SELECT coalesce(provider_image_revision, 1) FROM media WHERE id = ?", Integer.class, media);
        jdbc.update("""
                INSERT INTO verification_evidence (program_id, verification_id, inspection_id, role, media_id, media_version) VALUES (?, ?, ?, ?, ?, ?)""",
                program, verification, inspectionId, role, media, revision);
    }

    private ApiException changedEvidence(Map<String, Object> issue) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("issueId", issue.get("id"));
        details.put("currentStatus", issue.get("status"));
        details.put("currentRequestId", issue.get("current_reinspection_request_id"));
        details.put("currentVerificationId", null);
        return new ApiException(ErrorCode.STALE_VERIFICATION, "준비 뒤 증거 사진이 바뀌었습니다(다시 가림 등). 새로 준비해 주세요.", details);
    }

    /** 요청이 문제의 현재 요청이고 열려 있어야 한다(아니면 409 STALE_VERIFICATION). 요청 행을 잠근다(3). */
    private Map<String, Object> currentRequest(Map<String, Object> issue, UUID requestId) {
        if (!requestId.equals(issue.get("current_reinspection_request_id"))) {
            throw stale(issue, "지난 회차의 재점검 요청입니다. 최신 내용을 확인해 주세요.");
        }
        Map<String, Object> request = jdbc.queryForMap("SELECT id, version, status, repair_event_id FROM reinspection_requests WHERE id = ? FOR UPDATE",
                requestId);
        if (!"OPEN".equals(request.get("status"))) {
            throw stale(issue, "이미 끝난 재점검 요청입니다. 최신 내용을 확인해 주세요.");
        }
        return request;
    }

    private static ApiException stale(Map<String, Object> issue, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("issueId", issue.get("id"));
        details.put("currentStatus", issue.get("status"));
        details.put("currentRequestId", issue.get("current_reinspection_request_id"));
        details.put("currentVerificationId", null);
        return new ApiException(ErrorCode.STALE_VERIFICATION, message, details);
    }

    /**
     * 준비 뒤 요청 version이 바뀐 준비: 409 STALE_VERIFICATION. 더 새 준비가 있으면 그 ID(currentVerificationId)와 '대체됨' 안내,
     * 없으면(재배정·배정 취소·추가 확인 등으로 요청이 바뀜) null과 새 준비 안내(CONTRACT-01).
     */
    private ApiException supersededPreparation(Map<String, Object> issue, UUID requestId, long requestVersion) {
        UUID current = jdbc.queryForList("""
                SELECT id FROM verifications WHERE request_id = ? AND decision IS NULL AND prepared_request_version = ? ORDER BY created_at DESC, id
                LIMIT 1""", UUID.class, requestId, requestVersion).stream().findFirst().orElse(null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("issueId", issue.get("id"));
        details.put("currentStatus", issue.get("status"));
        details.put("currentRequestId", issue.get("current_reinspection_request_id"));
        details.put("currentVerificationId", current);
        String message = current != null ? "새 수리 확인 준비로 대체된 준비입니다. 최신 준비로 다시 판단해 주세요."
                : "준비한 뒤 재점검 배정이나 확인 방법이 바뀌어 이 준비로는 판단할 수 없습니다. 새로 준비해 주세요.";
        return new ApiException(ErrorCode.STALE_VERIFICATION, message, details);
    }

    private void lockInspections(List<UUID> a, List<UUID> b) {
        TreeSet<UUID> all = new TreeSet<>(a);
        all.addAll(b);
        for (UUID id : all) {
            jdbc.queryForList("SELECT id FROM inspections WHERE id = ? FOR SHARE", id);
        }
    }

    /** 이 문제에 유효하게 연결된 같은 시설 사진이고 현재 사본이 승인 상태. */
    private boolean validBefore(UUID issueId, UUID facilityId, UUID inspectionId) {
        Boolean linked = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM issue_inspections x JOIN inspections i ON i.id = x.inspection_id
                    JOIN capture_slots s ON s.id = i.capture_slot_id JOIN patrol_stops ps ON ps.id = s.patrol_stop_id
                    WHERE x.issue_id = ? AND x.inspection_id = ? AND x.unlinked_at IS NULL AND ps.facility_id = ?)""", Boolean.class, issueId, inspectionId,
                facilityId);
        return Boolean.TRUE.equals(linked) && approvedCurrentCopy(inspectionId);
    }

    /** 현재 요청의 취소되지 않은 배정 구도에서 채택된 사진이고 현재 사본이 승인 상태. */
    private boolean validAfter(UUID requestId, UUID inspectionId) {
        Boolean adopted = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM reinspection_assignments a JOIN capture_slots s ON s.id = a.capture_slot_id
                    WHERE a.request_id = ? AND a.cancelled_at IS NULL AND s.accepted_inspection_id = ?)""", Boolean.class, requestId, inspectionId);
        return Boolean.TRUE.equals(adopted) && approvedCurrentCopy(inspectionId);
    }

    /** 현재 사본(승인 포인터, 없으면 접수 사본의 최신 대체본)이 개인정보 승인 상태. */
    private boolean approvedCurrentCopy(UUID inspectionId) {
        UUID current = currentCopy(inspectionId);
        return current != null && approved(current);
    }

    /** 사진 시도의 현재 사본 ID(없으면 null). */
    private UUID currentCopy(UUID inspectionId) {
        return jdbc.queryForList("""
                WITH RECURSIVE chain(id, next) AS (
                    SELECT m.id, m.superseded_by_media_id FROM inspections i JOIN media m ON m.id = COALESCE(i.approved_media_id, i.media_id) WHERE i.id = ?
                    UNION ALL SELECT m.id, m.superseded_by_media_id FROM media m JOIN chain c ON m.id = c.next)
                SELECT id FROM chain WHERE next IS NULL""", UUID.class, inspectionId).stream().findFirst().orElse(null);
    }

    /** media 행을 공유 잠금하고(13) 개인정보 승인 상태인지. */
    private boolean approved(UUID mediaId) {
        String status = jdbc.queryForList("SELECT privacy_status FROM media WHERE id = ? AND deleted_at IS NULL FOR SHARE", String.class, mediaId)
                .stream().findFirst().orElse(null);
        return "APPROVED".equals(status);
    }

    /** 요청 version을 올리고 상태(null이면 유지)·유형(null이면 유지)을 바꾼다. 새 version. */
    private long bumpRequest(UUID requestId, String status, String requestType, Instant now) {
        return jdbc.queryForObject("""
                UPDATE reinspection_requests SET status = COALESCE(?, status), request_type = COALESCE(?, request_type), version = version + 1,
                    updated_at = ? WHERE id = ? RETURNING version""", Long.class, status, requestType, Timestamp.from(now), requestId);
    }

    private static void requireVersion(Map<String, Object> row, Long expected, String which) {
        long current = ((Number) row.get("version")).longValue();
        if (current != expected) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.",
                    Map.of("currentVersion", current, "which", which));
        }
    }

    private static void idList(List<FieldError> e, String field, List<UUID> ids, boolean required) {
        if (ids == null) {
            e.add(new FieldError(field, "REQUIRED", "사진 목록이 필요합니다(없으면 빈 목록)."));
        } else if (ids.contains(null) || new LinkedHashSet<>(ids).size() != ids.size()) {
            e.add(new FieldError(field, "INVALID_FORMAT", "사진 ID가 비었거나 중복입니다."));
        } else if (ids.size() > 20) {
            e.add(new FieldError(field, "TOO_LONG", "20장 이하입니다."));
        } else if (required && ids.isEmpty()) {
            e.add(new FieldError(field, "REQUIRED", "사진 확인은 수리 전·후 사진이 각각 1장 이상 필요합니다."));
        }
    }

    private static void versionField(List<FieldError> e, String field, Long v) {
        if (v == null || v < 0) {
            e.add(new FieldError(field, v == null ? "REQUIRED" : "OUT_OF_RANGE", "version이 필요합니다."));
        }
    }

    private static void fail(List<FieldError> e) {
        if (!e.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, e);
        }
    }
}
