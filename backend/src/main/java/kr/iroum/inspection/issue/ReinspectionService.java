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

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse.FieldError;
import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.issue.IssueViews.AssignmentResult;
import kr.iroum.inspection.issue.IssueViews.CancellationResult;
import kr.iroum.inspection.issue.IssueViews.ReinspectionRequestItem;
import kr.iroum.inspection.review.ReviewQueryService;
import kr.iroum.inspection.review.ReviewReader;
import kr.iroum.inspection.review.ReviewViews.FacilitySummary;
import kr.iroum.inspection.review.ReviewViews.Page;

/**
 * 재점검 요청 목록·배정·배정 취소(업무 API 10절, DB 설계 9절, REINSPECT-01).
 * <ul>
 * <li>목록(T9): 활동 담당자는 담당 수행기관·거점 순회가 들르는 시설의 요청, 시설 담당자는 현재 관할 시설의 요청. 문제 내용·등급은 넣지 않는다.</li>
 * <li>배정·취소(T10): 활동 담당자(재촬영 일정, 업무 API 3절)만. 구도는 같은 시설·purpose=REINSPECTION·출발 전 담당 순회의 열린 구도.
 * 한 요청의 활성 배정은 한 순회 안에만 있고, 다른 출발 전 순회에 있던 활성 배정은 새 배정과 함께 취소한다. 출발한 배정은 취소하지 않는다(이력 보존).
 * 새 순회와 취소될 기존 순회 모두 요청자의 담당 범위여야 한다(아니면 404, REINSPECT-02).</li>
 * <li>잠금: 문제(2) → 요청(3) → 순회(6, ID순) → 구도(10). 요청 version·순회 version 검사, 순회 version·revision을 올려 앱 동기화에 반영한다.</li>
 * </ul>
 */
@Service
public class ReinspectionService {

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final IssueQueryService issues;
    private final IdempotencyService idempotency;
    private final AuditLog audit;
    private final Clock clock;

    public ReinspectionService(JdbcTemplate jdbc, AccessPolicy access, IssueQueryService issues, IdempotencyService idempotency, AuditLog audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.issues = issues;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ReinspectionRequestItem> list(AuthenticatedUser user, String status, UUID organizationId, Integer page, Integer size) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER, AccessPolicy.FACILITY_MANAGER);
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.max(1, Math.min(100, size));
        Set<UUID> facilities = new LinkedHashSet<>();
        if (user.hasRole(AccessPolicy.ACTIVITY_MANAGER)) {
            facilities.addAll(jdbc.queryForList(AccessPolicy.scopedOrganizationsCte() + """
                    SELECT DISTINCT ps.facility_id FROM patrol_stops ps JOIN patrols pa ON pa.id = ps.patrol_id
                    WHERE EXISTS (SELECT 1 FROM scoped sc WHERE sc.program_id = pa.program_id AND sc.id IN (pa.organization_id, pa.base_organization_id))
                      AND (?::uuid IS NULL OR ?::uuid IN (pa.organization_id, pa.base_organization_id))""", UUID.class,
                    user.userId(), AccessPolicy.ACTIVITY_MANAGER, organizationId, organizationId));
        }
        if (user.hasRole(AccessPolicy.FACILITY_MANAGER)) {
            facilities.addAll(jdbc.queryForList(AccessPolicy.scopedOrganizationsCte() + """
                    SELECT f.id FROM facilities f JOIN scoped sc ON sc.program_id = f.program_id AND sc.id = f.managing_department_id
                    WHERE (?::uuid IS NULL OR f.managing_department_id = ?::uuid)""", UUID.class,
                    user.userId(), AccessPolicy.FACILITY_MANAGER, organizationId, organizationId));
        }
        UUID[] ids = facilities.toArray(UUID[]::new);
        String where = " FROM reinspection_requests r JOIN facilities f ON f.id = r.facility_id WHERE r.facility_id = ANY(?) "
                + "AND (?::varchar IS NULL OR r.status = ?::varchar)";
        List<ReinspectionRequestItem> items = jdbc.query("""
                SELECT r.id, r.request_type, r.status, r.version, r.issue_id, r.review_decision_id, r.repair_event_id, r.created_at,
                    f.id AS facility_id, f.facility_code, f.type, f.name, f.area_label""" + where + " ORDER BY r.created_at, r.id LIMIT " + s
                + " OFFSET " + ((long) p * s),
                (rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new ReinspectionRequestItem(id, rs.getString("request_type"), rs.getString("status"), rs.getLong("version"),
                            rs.getObject("repair_event_id") != null ? "REPAIR_COMPLETION" : "REVIEW_FOLLOW_UP", rs.getObject("issue_id", UUID.class),
                            rs.getObject("review_decision_id", UUID.class), rs.getObject("repair_event_id", UUID.class),
                            new FacilitySummary(rs.getObject("facility_id", UUID.class), rs.getString("facility_code"), rs.getString("type"),
                                    rs.getString("name"), rs.getString("area_label") == null ? "" : rs.getString("area_label")),
                            ReviewReader.ts(rs.getObject("created_at", OffsetDateTime.class)), issues.assignments(id));
                },
                ids, status, status);
        Long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, ids, status, status);
        return new Page<>(items, p, s, total == null ? 0 : total);
    }

    @Transactional
    public IdempotencyService.Outcome<AssignmentResult> assign(AuthenticatedUser user, UUID requestId, UUID key, UUID captureSlotId, Long version,
            Long patrolVersion) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        List<FieldError> e = new ArrayList<>();
        if (captureSlotId == null) {
            e.add(new FieldError("captureSlotId", "REQUIRED", "배정할 구도가 필요합니다."));
        }
        versionFields(e, version, patrolVersion);
        fail(e);
        UUID patrolId = jdbc.queryForList("""
                SELECT ps.patrol_id FROM capture_slots s JOIN patrol_stops ps ON ps.id = s.patrol_stop_id WHERE s.id = ?""", UUID.class, captureSlotId)
                .stream().findFirst().orElse(null);
        if (patrolId == null) {
            fail(List.of(new FieldError("captureSlotId", "INVALID_FORMAT", "배정할 수 있는 구도가 아닙니다.")));
        }
        UUID program = access.requireManagedPatrol(user, patrolId);
        requireRequest(requestId, program);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("requestId", requestId);
        identity.put("captureSlotId", captureSlotId);
        identity.put("version", version);
        identity.put("patrolVersion", patrolVersion);
        return idempotency.execute(user.userId(), "POST /admin/reinspection-requests/{requestId}/assignments", key, identity, AssignmentResult.class,
                201, () -> {
                    Map<String, Object> request = lockRequest(requestId);
                    requireVersion(request, version, "request");
                    if (!"OPEN".equals(request.get("status"))) {
                        throw stale(request);
                    }
                    List<Map<String, Object>> active = jdbc.queryForList("""
                            SELECT a.id, a.patrol_id, a.capture_slot_id FROM reinspection_assignments a WHERE a.request_id = ?
                              AND a.cancelled_at IS NULL AND a.completed_at IS NULL ORDER BY a.id""", requestId);
                    TreeSet<UUID> patrols = new TreeSet<>();
                    patrols.add(patrolId);
                    active.forEach(a -> patrols.add((UUID) a.get("patrol_id")));
                    Map<UUID, Map<String, Object>> locked = new LinkedHashMap<>();
                    for (UUID id : patrols) {
                        locked.put(id, jdbc.queryForMap("""
                                SELECT id, version, activity_status, departed_at, departure_confirmed_at FROM patrols WHERE id = ? FOR UPDATE""", id));
                    }
                    // 바뀌는 모든 순회(새 배정 순회와, 재배정으로 활성 배정이 취소될 기존 순회)가 이 활동 담당자의 범위여야 한다.
                    // 범위 밖이면 404이고 트랜잭션 전체(멱등 기록 포함)를 되돌린다(REINSPECT-02). 범위 밖 배정 이동 권한은 주지 않는다.
                    for (UUID id : patrols) {
                        access.requireManagedPatrol(user, id);
                    }
                    Map<String, Object> target = locked.get(patrolId);
                    requireVersion(target, patrolVersion, "patrol");
                    requireNotDeparted(target);
                    Map<String, Object> slot = jdbc.queryForMap("""
                            SELECT s.id, s.patrol_stop_id, s.purpose, s.resolution, s.active_inspection_id, s.accepted_inspection_id, ps.facility_id
                            FROM capture_slots s JOIN patrol_stops ps ON ps.id = s.patrol_stop_id WHERE s.id = ? FOR UPDATE OF s""", captureSlotId);
                    boolean usedElsewhere = Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM reinspection_assignments WHERE capture_slot_id = ? AND cancelled_at IS NULL
                                AND completed_at IS NULL)""", Boolean.class, captureSlotId));
                    if (!"REINSPECTION".equals(slot.get("purpose")) || !"OPEN".equals(slot.get("resolution"))
                            || slot.get("active_inspection_id") != null || !request.get("facility_id").equals(slot.get("facility_id")) || usedElsewhere) {
                        fail(List.of(new FieldError("captureSlotId", "INVALID_FORMAT",
                                "같은 시설의 출발 전 재점검 구도이고 다른 배정이 없어야 합니다.")));
                    }
                    Instant now = clock.instant();
                    List<UUID> cancelled = new ArrayList<>();
                    for (Map<String, Object> a : active) {
                        UUID other = (UUID) a.get("patrol_id");
                        if (other.equals(patrolId)) {
                            continue;
                        }
                        // 한 요청의 활성 배정은 한 순회 안에만 둔다. 출발한 순회의 배정은 이력이라 취소하지 않으므로 다른 순회로 옮길 수 없다.
                        requireNotDeparted(locked.get(other));
                        jdbc.update("UPDATE reinspection_assignments SET cancelled_at = ? WHERE id = ?", Timestamp.from(now), a.get("id"));
                        bumpPatrol(other, now);
                        cancelled.add((UUID) a.get("id"));
                    }
                    UUID assignment = jdbc.queryForObject("""
                            INSERT INTO reinspection_assignments (program_id, request_id, capture_slot_id, patrol_stop_id, patrol_id, assigned_by,
                                assigned_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, program, requestId, captureSlotId,
                            slot.get("patrol_stop_id"), patrolId, user.userId(),
                            Timestamp.from(now));
                    long requestVersion = bumpRequest(requestId, now);
                    long nextPatrolVersion = bumpPatrol(patrolId, now);
                    audit.record(program, user.userId(), "REINSPECTION_ASSIGNED", "REINSPECTION_REQUEST", requestId, null,
                            Map.of("activeAssignmentIds", active.stream().map(a -> a.get("id")).toList()),
                            Map.of("assignmentId", assignment, "captureSlotId", captureSlotId, "patrolId", patrolId, "cancelledAssignmentIds", cancelled));
                    return new AssignmentResult(assignment, requestId, requestVersion, captureSlotId, patrolId, nextPatrolVersion, cancelled);
                });
    }

    @Transactional
    public IdempotencyService.Outcome<CancellationResult> cancel(AuthenticatedUser user, UUID assignmentId, UUID key, String reason, Long version,
            Long patrolVersion) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        Map<String, Object> a = jdbc.queryForList("SELECT request_id, patrol_id FROM reinspection_assignments WHERE id = ?", assignmentId).stream()
                .findFirst().orElseThrow(ReviewQueryService::notFound);
        UUID program = access.requireManagedPatrol(user, (UUID) a.get("patrol_id"));
        List<FieldError> e = new ArrayList<>();
        if (reason == null || reason.isBlank()) {
            e.add(new FieldError("reason", "REQUIRED", "취소 사유가 필요합니다."));
        } else if (reason.length() > 500) {
            e.add(new FieldError("reason", "TOO_LONG", "500자 이하여야 합니다."));
        }
        versionFields(e, version, patrolVersion);
        fail(e);
        UUID requestId = (UUID) a.get("request_id");
        UUID patrolId = (UUID) a.get("patrol_id");
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("assignmentId", assignmentId);
        identity.put("reason", reason);
        identity.put("version", version);
        identity.put("patrolVersion", patrolVersion);
        return idempotency.execute(user.userId(), "POST /admin/reinspection-assignments/{assignmentId}/cancellations", key, identity,
                CancellationResult.class, 200, () -> {
                    Map<String, Object> request = lockRequest(requestId);
                    requireVersion(request, version, "request");
                    Map<String, Object> patrol = jdbc.queryForMap("""
                            SELECT id, version, activity_status, departed_at, departure_confirmed_at FROM patrols WHERE id = ? FOR UPDATE""", patrolId);
                    requireVersion(patrol, patrolVersion, "patrol");
                    requireNotDeparted(patrol);
                    Map<String, Object> row = jdbc.queryForMap("SELECT cancelled_at, completed_at FROM reinspection_assignments WHERE id = ? FOR UPDATE",
                            assignmentId);
                    if (row.get("cancelled_at") != null || row.get("completed_at") != null) {
                        throw stale(request);
                    }
                    Instant now = clock.instant();
                    jdbc.update("UPDATE reinspection_assignments SET cancelled_at = ? WHERE id = ?", Timestamp.from(now), assignmentId);
                    long requestVersion = bumpRequest(requestId, now);
                    long nextPatrolVersion = bumpPatrol(patrolId, now);
                    audit.record(program, user.userId(), "REINSPECTION_ASSIGNMENT_CANCELLED", "REINSPECTION_REQUEST", requestId, reason.strip(),
                            Map.of("assignmentId", assignmentId), Map.of("assignmentId", assignmentId, "cancelledAt", now.toString()));
                    return new CancellationResult(assignmentId, requestId, requestVersion, patrolId, nextPatrolVersion, now);
                });
    }

    /** 요청이 순회와 같은 사업에 있는지(없으면 404). */
    private void requireRequest(UUID requestId, UUID program) {
        Boolean ok = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM reinspection_requests WHERE id = ? AND program_id = ?)", Boolean.class,
                requestId, program);
        if (!Boolean.TRUE.equals(ok)) {
            throw ReviewQueryService.notFound();
        }
    }

    /** 잠금 순서: 문제(2) → 요청(3). */
    private Map<String, Object> lockRequest(UUID requestId) {
        UUID issue = jdbc.queryForObject("SELECT issue_id FROM reinspection_requests WHERE id = ?", UUID.class, requestId);
        if (issue != null) {
            jdbc.queryForList("SELECT id FROM issues WHERE id = ? FOR UPDATE", issue);
        }
        return jdbc.queryForMap("SELECT id, version, status, facility_id FROM reinspection_requests WHERE id = ? FOR UPDATE", requestId);
    }

    private static void requireVersion(Map<String, Object> row, Long expected, String which) {
        long current = ((Number) row.get("version")).longValue();
        if (current != expected) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("currentVersion", current);
            details.put("which", which);
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.", details);
        }
    }

    private static ApiException stale(Map<String, Object> request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currentVersion", ((Number) request.get("version")).longValue());
        details.put("which", "request");
        return new ApiException(ErrorCode.VERSION_CONFLICT, "재점검 요청이 이미 끝났거나 배정이 바뀌었습니다. 최신 내용을 확인해 주세요.", details);
    }

    /** 출발 전(배정 상태, 출발·출발 확인 기록 없음)이어야 한다. 취소된 순회는 409 PATROL_CANCELLED. */
    private static void requireNotDeparted(Map<String, Object> patrol) {
        String status = (String) patrol.get("activity_status");
        if ("CANCELLED".equals(status)) {
            throw new ApiException(ErrorCode.PATROL_CANCELLED, "취소된 순회입니다.");
        }
        if (!"ASSIGNED".equals(status) || patrol.get("departed_at") != null || patrol.get("departure_confirmed_at") != null) {
            // 활동 상태 충돌 공통 형식: reason + activityStatus(업무 API 2.2절, OpenAPI ErrorActivityStateConflict)
            throw new ApiException(ErrorCode.ACTIVITY_STATE_CONFLICT, "이미 출발한 순회의 재점검 배정은 바꿀 수 없습니다.",
                    Map.of("reason", "ALREADY_DEPARTED", "activityStatus", status));
        }
    }

    private long bumpRequest(UUID requestId, Instant now) {
        return jdbc.queryForObject("UPDATE reinspection_requests SET version = version + 1, updated_at = ? WHERE id = ? RETURNING version", Long.class,
                Timestamp.from(now), requestId);
    }

    private long bumpPatrol(UUID patrolId, Instant now) {
        return jdbc.queryForObject("""
                UPDATE patrols SET version = version + 1, revision = revision + 1, updated_at = ? WHERE id = ? RETURNING version""", Long.class,
                Timestamp.from(now), patrolId);
    }

    private static void versionFields(List<FieldError> e, Long version, Long patrolVersion) {
        if (version == null || version < 0) {
            e.add(new FieldError("version", version == null ? "REQUIRED" : "OUT_OF_RANGE", "재점검 요청 version이 필요합니다."));
        }
        if (patrolVersion == null || patrolVersion < 0) {
            e.add(new FieldError("patrolVersion", patrolVersion == null ? "REQUIRED" : "OUT_OF_RANGE", "순회 version이 필요합니다."));
        }
    }

    private static void fail(List<FieldError> e) {
        if (!e.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, e);
        }
    }
}
