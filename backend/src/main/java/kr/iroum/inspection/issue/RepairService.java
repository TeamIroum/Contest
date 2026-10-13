package kr.iroum.inspection.issue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

/**
 * 보수 요청·착수·완료(업무 API 10절, DB 설계 9절, REPAIR-01). 시설 담당자(현재 관할)만.
 * <ul>
 * <li>상태: OPEN → REPAIR_REQUESTED(요청) → IN_REPAIR(착수) → AWAITING_VERIFICATION(완료). 맞지 않는 상태는 409 ISSUE_STATE_CONFLICT,
 * 문제 version이 다르면 409 VERSION_CONFLICT. 재보수 요청(RE_REQUESTED)은 수리 확인 판정 REPAIR_AGAIN이 만든다.</li>
 * <li>보수 완료 이벤트 + 문제 AWAITING_VERIFICATION + 재점검 요청 생성 + 현재 요청 포인터는 한 트랜잭션이다(10절). 재점검 요청 유형은 REPHOTO(T5).</li>
 * <li>증거 사진(evidenceMediaIds)은 이 문제로 올린(POST /admin/media issueId) REPAIR_EVIDENCE·개인정보 승인·현재 사본이며 다른 보수 이벤트에 쓰이지
 * 않은 것만(T6, MEDIA-UP-01).</li>
 * <li>잠금: 시설(1, 공유, 현재 관할 재확인) → 문제(2) → 재점검 요청 추가(3) → 증거 media(13, 공유).</li>
 * </ul>
 */
@Service
public class RepairService {

    /** 완료 시각 입력 오차 허용(기기 시계 차이). 시각 입력 허용 오차는 미정이라 정정 API와 같은 보수적 값을 쓴다. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    public record RepairResult(UUID issueId, String issueStatus, long issueVersion, UUID repairEventId, UUID reinspectionRequestId) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final IssueLocks locks;
    private final IdempotencyService idempotency;
    private final AuditLog audit;
    private final Clock clock;

    public RepairService(JdbcTemplate jdbc, AccessPolicy access, IssueLocks locks, IdempotencyService idempotency, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.locks = locks;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyService.Outcome<RepairResult> request(AuthenticatedUser user, UUID issueId, UUID key, UUID assignedDepartmentId, String memo,
            Long version) {
        locks.requireScope(user, issueId);
        List<FieldError> e = new ArrayList<>();
        if (assignedDepartmentId == null) {
            e.add(new FieldError("assignedDepartmentId", "REQUIRED", "보수 담당 부서가 필요합니다."));
        }
        if (memo != null && memo.length() > 1000) {
            e.add(new FieldError("memo", "TOO_LONG", "1000자 이하여야 합니다."));
        }
        requireVersionField(e, version);
        fail(e);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("issueId", issueId);
        identity.put("assignedDepartmentId", assignedDepartmentId);
        identity.put("memo", memo);
        identity.put("version", version);
        return idempotency.execute(user.userId(), "POST /admin/issues/{issueId}/repair-requests", key, identity, RepairResult.class, 201, () -> {
            Map<String, Object> issue = locks.lock(user, issueId);
            IssueLocks.requireVersion(issue, version);
            IssueLocks.requireStatus(issue, "OPEN");
            UUID program = (UUID) issue.get("program_id");
            // T4: 보수 담당 부서는 같은 사업의 부서이며 요청자 시설 담당 범위 안이어야 한다(다른 부서 지정은 결정 필요).
            Boolean department = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM organizations WHERE id = ? AND program_id = ? AND type = 'DEPARTMENT')",
                    Boolean.class, assignedDepartmentId, program);
            if (!Boolean.TRUE.equals(department) || !access.coversOrganization(user, AccessPolicy.FACILITY_MANAGER, program, assignedDepartmentId)) {
                fail(List.of(new FieldError("assignedDepartmentId", "INVALID_FORMAT", "보수를 맡길 수 있는 부서가 아닙니다.")));
            }
            Instant now = clock.instant();
            String text = memo == null || memo.isBlank() ? null : memo.strip();
            UUID event = event(program, issueId, "REQUESTED", user.userId(), assignedDepartmentId, text, now);
            long next = update(issueId, "REPAIR_REQUESTED", assignedDepartmentId, null, now);
            audit.record(program, user.userId(), "REPAIR_REQUESTED", "ISSUE", issueId, text,
                    Map.of("status", "OPEN", "assignedDepartmentId", issue.get("assigned_department_id")),
                    Map.of("status", "REPAIR_REQUESTED", "assignedDepartmentId", assignedDepartmentId, "repairEventId", event));
            return new RepairResult(issueId, "REPAIR_REQUESTED", next, event, null);
        });
    }

    @Transactional
    public IdempotencyService.Outcome<RepairResult> start(AuthenticatedUser user, UUID issueId, UUID key, Long version) {
        locks.requireScope(user, issueId);
        List<FieldError> e = new ArrayList<>();
        requireVersionField(e, version);
        fail(e);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("issueId", issueId);
        identity.put("version", version);
        return idempotency.execute(user.userId(), "POST /admin/issues/{issueId}/repair-starts", key, identity, RepairResult.class, 201, () -> {
            Map<String, Object> issue = locks.lock(user, issueId);
            IssueLocks.requireVersion(issue, version);
            IssueLocks.requireStatus(issue, "REPAIR_REQUESTED");
            UUID program = (UUID) issue.get("program_id");
            Instant now = clock.instant();
            UUID department = (UUID) issue.get("assigned_department_id");
            UUID event = event(program, issueId, "STARTED", user.userId(), department, null, now);
            long next = update(issueId, "IN_REPAIR", department, null, now);
            audit.record(program, user.userId(), "REPAIR_STARTED", "ISSUE", issueId, null, Map.of("status", "REPAIR_REQUESTED"),
                    Map.of("status", "IN_REPAIR", "repairEventId", event));
            return new RepairResult(issueId, "IN_REPAIR", next, event, null);
        });
    }

    @Transactional
    public IdempotencyService.Outcome<RepairResult> complete(AuthenticatedUser user, UUID issueId, UUID key, String description, Instant completedAt,
            List<UUID> evidenceMediaIds, Long version) {
        locks.requireScope(user, issueId);
        List<FieldError> e = new ArrayList<>();
        if (description == null || description.isBlank()) {
            e.add(new FieldError("description", "REQUIRED", "보수 내용이 필요합니다."));
        } else if (description.length() > 1000) {
            e.add(new FieldError("description", "TOO_LONG", "1000자 이하여야 합니다."));
        }
        if (completedAt == null) {
            e.add(new FieldError("completedAt", "REQUIRED", "보수 완료 시각이 필요합니다."));
        } else if (completedAt.isAfter(clock.instant().plus(CLOCK_SKEW))) {
            e.add(new FieldError("completedAt", "OUT_OF_RANGE", "미래 시각은 넣을 수 없습니다."));
        }
        if (evidenceMediaIds == null) {
            e.add(new FieldError("evidenceMediaIds", "REQUIRED", "증거 사진 목록이 필요합니다(없으면 빈 목록)."));
        } else if (evidenceMediaIds.contains(null) || new LinkedHashSet<>(evidenceMediaIds).size() != evidenceMediaIds.size()) {
            e.add(new FieldError("evidenceMediaIds", "INVALID_FORMAT", "증거 사진 ID가 비었거나 중복입니다."));
        } else if (evidenceMediaIds.size() > 10) {
            e.add(new FieldError("evidenceMediaIds", "TOO_LONG", "증거 사진은 10장 이하입니다."));
        }
        requireVersionField(e, version);
        fail(e);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("issueId", issueId);
        identity.put("description", description);
        identity.put("completedAt", completedAt.toString());
        identity.put("evidenceMediaIds", evidenceMediaIds);
        identity.put("version", version);
        return idempotency.execute(user.userId(), "POST /admin/issues/{issueId}/repair-completions", key, identity, RepairResult.class, 201, () -> {
            Map<String, Object> issue = locks.lock(user, issueId);
            IssueLocks.requireVersion(issue, version);
            IssueLocks.requireStatus(issue, "IN_REPAIR");
            UUID program = (UUID) issue.get("program_id");
            Instant started = jdbc.queryForObject("""
                    SELECT max(occurred_at) FROM repair_events WHERE issue_id = ? AND type = 'STARTED'""", Timestamp.class, issueId).toInstant();
            if (completedAt.isBefore(started)) {
                fail(List.of(new FieldError("completedAt", "OUT_OF_RANGE", "보수 착수 시각보다 앞설 수 없습니다.")));
            }
            List<FieldError> evidence = new ArrayList<>();
            for (UUID media : new TreeSet<>(evidenceMediaIds)) {
                Map<String, Object> m = jdbc.queryForList("""
                        SELECT purpose, privacy_status, superseded_by_media_id, deleted_at FROM media WHERE id = ? AND program_id = ? AND issue_id = ?
                        FOR SHARE""", media, program, issueId).stream().findFirst().orElse(null);
                boolean used = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM repair_event_media WHERE media_id = ?)",
                        Boolean.class, media));
                if (m == null || !"REPAIR_EVIDENCE".equals(m.get("purpose")) || !"APPROVED".equals(m.get("privacy_status"))
                        || m.get("superseded_by_media_id") != null || m.get("deleted_at") != null || used) {
                    evidence.add(new FieldError("evidenceMediaIds[" + evidenceMediaIds.indexOf(media) + "]", "INVALID_FORMAT",
                            "승인된 보수 증거 사진이 아니거나 이미 다른 보수에 쓰였습니다."));
                }
            }
            fail(evidence);
            Instant now = clock.instant();
            UUID department = (UUID) issue.get("assigned_department_id");
            UUID event = event(program, issueId, "COMPLETED", user.userId(), department, description.strip(), completedAt);
            for (UUID media : evidenceMediaIds) {
                jdbc.update("INSERT INTO repair_event_media (program_id, repair_event_id, media_id) VALUES (?, ?, ?)", program, event, media);
            }
            UUID request = jdbc.queryForObject("""
                    INSERT INTO reinspection_requests (program_id, facility_id, issue_id, repair_event_id, request_type, status, requested_by, created_at,
                        updated_at) VALUES (?, ?, ?, ?, 'REPHOTO', 'OPEN', ?, ?, ?) RETURNING id""", UUID.class,
                    program, issue.get("facility_id"), issueId, event, user.userId(), Timestamp.from(now), Timestamp.from(now));
            long next = update(issueId, "AWAITING_VERIFICATION", department, request, now);
            audit.record(program, user.userId(), "REPAIR_COMPLETED", "ISSUE", issueId, description.strip(), Map.of("status", "IN_REPAIR"),
                    Map.of("status", "AWAITING_VERIFICATION", "repairEventId", event, "reinspectionRequestId", request,
                            "evidenceMediaIds", evidenceMediaIds, "completedAt", completedAt.toString()));
            return new RepairResult(issueId, "AWAITING_VERIFICATION", next, event, request);
        });
    }

    private UUID event(UUID program, UUID issueId, String type, UUID actor, UUID department, String description, Instant occurredAt) {
        return jdbc.queryForObject("""
                INSERT INTO repair_events (program_id, issue_id, type, actor_user_id, department_id, description, occurred_at, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, program, issueId, type, actor, department, description,
                Timestamp.from(occurredAt), Timestamp.from(clock.instant()));
    }

    /** 상태·담당 부서·현재 요청(null이면 유지)을 바꾸고 version을 올린다. 새 version. */
    private long update(UUID issueId, String status, UUID department, UUID request, Instant now) {
        return jdbc.queryForObject("""
                UPDATE issues SET status = ?, assigned_department_id = ?, current_reinspection_request_id = COALESCE(?, current_reinspection_request_id),
                    version = version + 1, updated_at = ? WHERE id = ? RETURNING version""", Long.class, status, department, request,
                Timestamp.from(now), issueId);
    }

    private static void requireVersionField(List<FieldError> e, Long version) {
        if (version == null || version < 0) {
            e.add(new FieldError("version", version == null ? "REQUIRED" : "OUT_OF_RANGE", "문제 version이 필요합니다."));
        }
    }

    private static void fail(List<FieldError> e) {
        if (!e.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, e);
        }
    }
}
