package kr.iroum.inspection.patrol.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.patrol.PatrolReader;
import kr.iroum.inspection.patrol.PatrolReader.MemberRow;
import kr.iroum.inspection.patrol.PatrolReader.PatrolRow;

/**
 * 활동 중 조 이동(D-34·D-36, 설계안 4절). 관리자가 출발 후에도 같은 기관·거점·활동일의 진행 중인 조로 조원을 옮긴다.
 * <ul>
 * <li>두 순회를 id 순서로 잠근 뒤 두 version과 상태·정원(현장 조원 3명)을 다시 검사한다. 정원은 이동 기록 때 DB 트리거로도 확인한다.</li>
 * <li>이동은 복귀가 아니다. 개인 시작·출결은 새 배정으로 이어받고(CARRIED) 이전 조의 기록·사진·참여 구간은 남긴다.</li>
 * <li>이동으로 이전 조의 현장 조원이 없어지면 그 조를 끝낸다(종료 기록, 계기 LAST_MEMBER_TRANSFERRED).</li>
 * <li>출발 전 조원 변경은 재편성(TeamFormationService)이며 이동 API는 받지 않는다(SOURCE_NOT_ACTIVE).</li>
 * </ul>
 */
@Service
public class TransferService {

    private static final int CAPACITY = 3;

    public record Candidate(UUID patrolId, String teamName, String routeName, String activityStatus, int inField, boolean full,
            int remainingStops, Instant scheduledStartAt, Instant expectedReturnAt, long version) {
    }

    public record Candidates(UUID patrolId, UUID participantId, List<Candidate> candidates) {
    }

    public record Side(UUID patrolId, String activityStatus, long version, long patrolRevision, int inField) {
    }

    public record TransferredMember(UUID participantId, UUID assignmentRef, int seatNo, String attendance, Instant startedAt,
            String startSource) {
    }

    public record TransferResult(UUID transferId, Instant transferredAt, UUID participantId, String reason, Side source, Side target,
            TransferredMember member) {
    }

    private final PatrolCommandSupport support;
    private final PatrolReader reader;
    private final AccessPolicy access;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    public TransferService(PatrolCommandSupport support, PatrolReader reader, AccessPolicy access, IdempotencyService idempotency,
            JdbcTemplate jdbc, AuditLog audit) {
        this.support = support;
        this.reader = reader;
        this.access = access;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** 옮길 수 있는 조: 같은 사업·기관·거점·활동일, IN_PROGRESS, 현장 조원 1명 이상, 담당 범위 안. 가득 찬 조도 full=true로 보인다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Candidates candidates(AuthenticatedUser user, UUID patrolId, UUID participantId) {
        access.requireManagedPatrol(user, patrolId);
        PatrolRow source = reader.patrol(patrolId).orElseThrow();
        support.activeMember(source, participantId);
        List<UUID> ids = jdbc.queryForList(AccessPolicy.scopedOrganizationsCte() + """
                SELECT p.id FROM patrols p
                WHERE p.program_id = ? AND p.organization_id = ? AND p.base_organization_id = ? AND p.activity_date = ? AND p.id <> ?
                  AND p.activity_status = 'IN_PROGRESS'
                  AND EXISTS (SELECT 1 FROM scoped sc WHERE sc.program_id = p.program_id AND sc.id IN (p.organization_id, p.base_organization_id))
                ORDER BY p.scheduled_start_at, p.id""", UUID.class, user.userId(), AccessPolicy.ACTIVITY_MANAGER, source.programId(),
                source.organizationId(), source.baseOrganizationId(), source.activityDate(), patrolId);
        List<Candidate> items = new java.util.ArrayList<>();
        for (UUID id : ids) {
            PatrolRow p = reader.patrol(id).orElseThrow();
            int inField = inField(reader.members(id));
            if (inField == 0) {
                continue;
            }
            int remaining = (int) reader.stops(id).stream().filter(s -> "PENDING".equals(s.visitStatus())).count();
            items.add(new Candidate(id, p.teamName(), p.routeName(), p.status(), inField, inField >= CAPACITY, remaining, p.scheduledStartAt(),
                    p.expectedReturnAt(), p.version()));
        }
        return new Candidates(patrolId, participantId, items);
    }

    @Transactional
    public IdempotencyService.Outcome<TransferResult> transfer(AuthenticatedUser user, UUID patrolId, UUID participantId, UUID targetPatrolId,
            long sourceVersion, long targetVersion, String reason, UUID key) {
        UUID programId = access.requireManagedPatrol(user, patrolId);
        access.requireManagedPatrol(user, targetPatrolId);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("patrolId", patrolId);
        identity.put("participantId", participantId);
        identity.put("targetPatrolId", targetPatrolId);
        identity.put("sourceVersion", sourceVersion);
        identity.put("targetVersion", targetVersion);
        identity.put("reason", reason);
        return idempotency.execute(user.userId(), "POST /admin/patrols/{id}/members/{participantId}/transfers", key, identity,
                TransferResult.class, 200, () -> {
                    if (patrolId.equals(targetPatrolId)) {
                        PatrolRow same = support.lock(patrolId);
                        throw support.stateConflict("SAME_PATROL", same, "같은 조로는 옮길 수 없어요.");
                    }
                    // 잠금 순서: 두 순회를 id 순서로(교착 방지), 각 순회의 배정 행까지(DB 설계 12절을 두 조로 확장).
                    Map<UUID, PatrolRow> locked = new LinkedHashMap<>();
                    for (UUID id : new TreeSet<>(Set.of(patrolId, targetPatrolId))) {
                        locked.put(id, support.lock(id));
                    }
                    PatrolRow source = locked.get(patrolId);
                    PatrolRow target = locked.get(targetPatrolId);
                    requireVersion(source, sourceVersion, "source");
                    requireVersion(target, targetVersion, "target");
                    if (!source.programId().equals(target.programId()) || !source.organizationId().equals(target.organizationId())
                            || !source.baseOrganizationId().equals(target.baseOrganizationId())
                            || !source.activityDate().equals(target.activityDate())) {
                        throw support.stateConflict("TARGET_SCOPE_MISMATCH", target, "같은 기관·거점·활동일의 조로만 옮길 수 있어요.");
                    }
                    if (!"IN_PROGRESS".equals(source.status()) && !"RETURNING".equals(source.status())) {
                        throw support.stateConflict("SOURCE_NOT_ACTIVE", source, "활동 중인 조에서만 옮길 수 있어요. 출발 전이면 재편성해 주세요.");
                    }
                    MemberRow member = reader.members(patrolId).stream().filter(m -> m.participantId().equals(participantId)).findFirst()
                            .orElseThrow(() -> reader.transferredTo(patrolId, participantId).isPresent()
                                    ? support.stateConflict("MEMBER_NOT_ASSIGNED", source, "이미 다른 조로 옮긴 조원이에요.")
                                    : new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다."));
                    if (member.absent()) {
                        throw support.stateConflict("MEMBER_ABSENT", source, "결석으로 확정된 조원은 옮길 수 없어요.");
                    }
                    if (member.returned()) {
                        throw support.stateConflict("MEMBER_RETURNED", source, "이미 복귀를 확인한 조원이에요.");
                    }
                    List<MemberRow> targetMembers = reader.members(targetPatrolId);
                    if (!"IN_PROGRESS".equals(target.status()) || inField(targetMembers) == 0) {
                        throw support.stateConflict("TARGET_NOT_ACTIVE", target, "합류할 조가 활동 중이 아니에요.");
                    }
                    if (targetMembers.stream().anyMatch(m -> m.participantId().equals(participantId))) {
                        throw support.stateConflict("MEMBER_ALREADY_IN_TARGET", target, "이미 그 조에 배정된 조원이에요.");
                    }
                    if (inField(targetMembers) >= CAPACITY) {
                        throw support.stateConflict("TARGET_FULL", target, "그 조는 이미 3명이에요.");
                    }
                    Instant now = support.now();
                    UUID sourceParticipation = jdbc.queryForObject("""
                            SELECT id FROM patrol_participations WHERE patrol_id = ? AND participant_id = ? AND leave_reason IS NULL""",
                            UUID.class, patrolId, participantId);
                    jdbc.update("""
                            UPDATE patrol_members SET assignment_active = false, version = version + 1, updated_at = now()
                            WHERE patrol_id = ? AND participant_id = ?""", patrolId, participantId);
                    int seat = freeSeat(targetPatrolId);
                    try {
                        join(target, member, seat);
                    } catch (DataIntegrityViolationException e) {
                        // 같은 참여자의 다른 배정과 시간이 겹치면 배타 제약이 막는다.
                        throw support.stateConflict("SCHEDULE_OVERLAP", target, "같은 시간에 다른 배정이 있어 옮길 수 없어요.");
                    }
                    UUID transferId = jdbc.queryForObject("""
                            INSERT INTO patrol_member_transfers (program_id, participant_id, from_patrol_id, to_patrol_id, transferred_at,
                                transferred_by, reason, from_version, to_version)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, programId, participantId, patrolId, targetPatrolId,
                            Timestamp.from(now), user.userId(), reason, sourceVersion, targetVersion);
                    // 닫는 구간에 그 구간의 개인 복귀 요청을 남긴다(D-33: 구간별 분리, 이력 보존). 배정 행 값은 다시 합류할 때 비운다.
                    jdbc.update("""
                            UPDATE patrol_participations pp SET leave_reason = 'TRANSFERRED', left_at = ?, transfer_out_id = ?,
                                requested_return_reason = m.requested_return_reason, requested_abort_reason = m.requested_abort_reason,
                                return_requested_at = m.return_requested_at, return_request_received_at = m.return_request_received_at
                            FROM patrol_members m
                            WHERE pp.id = ? AND m.patrol_id = pp.patrol_id AND m.participant_id = pp.participant_id""",
                            Timestamp.from(now), transferId, sourceParticipation);
                    jdbc.update("""
                            UPDATE patrol_participations SET joined_at = ?, transfer_in_id = ?
                            WHERE patrol_id = ? AND participant_id = ? AND leave_reason IS NULL""",
                            Timestamp.from(now), transferId, targetPatrolId, participantId);
                    // 이전 조: 현장 조원이 없어지면 종료. 새 조: 상태는 앞으로만(합류로 되돌리지 않음).
                    support.evaluate(patrolId, user.userId(), PatrolCommandSupport.EndTrigger.LAST_MEMBER_TRANSFERRED, participantId);
                    support.evaluate(targetPatrolId, user.userId(), null, participantId);
                    support.recomputeDepartedAt(patrolId);
                    support.recomputeDepartedAt(targetPatrolId);
                    long sourceNew = support.bumpVersion(patrolId);
                    long targetNew = support.bumpVersion(targetPatrolId);
                    Map<String, Object> before = new LinkedHashMap<>();
                    before.put("patrolId", patrolId.toString());
                    before.put("version", sourceVersion);
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("patrolId", targetPatrolId.toString());
                    after.put("version", targetVersion);
                    after.put("transferId", transferId.toString());
                    audit.record(programId, user.userId(), "PATROL_MEMBER_TRANSFERRED", "PATROL_MEMBER", participantId, reason, before, after);
                    PatrolRow s = support.reload(patrolId);
                    PatrolRow t = support.reload(targetPatrolId);
                    MemberRow moved = support.member(targetPatrolId, participantId);
                    UUID ref = jdbc.queryForObject("SELECT id FROM patrol_members WHERE patrol_id = ? AND participant_id = ?", UUID.class,
                            targetPatrolId, participantId);
                    return new TransferResult(transferId, now, participantId, reason,
                            new Side(patrolId, s.status(), sourceNew, s.revision(), inField(reader.members(patrolId))),
                            new Side(targetPatrolId, t.status(), targetNew, t.revision(), inField(reader.members(targetPatrolId))),
                            new TransferredMember(participantId, ref, moved.seatNo(), moved.attendance(), moved.startedAt(), moved.startSource()));
                });
    }

    /**
     * 새 조 배정: 행이 있으면(같은 조로 되돌아옴) 다시 활성화하고, 없으면 만든다. 출결·출석 요청·개인 시작을 이어받는다.
     * 그 조에서 직접 기록한 시작(SELF·ADMIN_CORRECTION)은 덮어쓰지 않는다. 다시 합류하면 개인 복귀 요청은 새 구간 것만 담도록 비운다
     * (이전 구간 요청은 그 구간 행과 이벤트에 남아 있다, ACT-17).
     */
    private void join(PatrolRow target, MemberRow m, int seat) {
        Timestamp start = m.startedAt() == null ? null : Timestamp.from(m.startedAt());
        Timestamp startReceived = m.startReceivedAt() == null ? null : Timestamp.from(m.startReceivedAt());
        int updated = jdbc.update("""
                UPDATE patrol_members SET assignment_active = true, seat_no = ?, attendance = ?, attendance_requested_at = ?,
                    attendance_request_received_at = ?, attendance_checked_by = ?, attendance_checked_at = ?, proxy_attendance_reason = ?,
                    paused = ?, paused_changed_at = ?,
                    requested_return_reason = NULL, requested_abort_reason = NULL, return_requested_at = NULL, return_request_received_at = NULL,
                    started_at = CASE WHEN started_at IS NULL THEN ? ELSE started_at END,
                    start_received_at = CASE WHEN started_at IS NULL THEN ? ELSE start_received_at END,
                    start_source = CASE WHEN started_at IS NULL AND ?::timestamptz IS NOT NULL THEN 'CARRIED' ELSE start_source END,
                    version = version + 1, updated_at = now()
                WHERE patrol_id = ? AND participant_id = ?""",
                seat, m.attendance(), ts(m.attendanceRequestedAt()), ts(m.attendanceRequestReceivedAt()), m.attendanceCheckedBy(),
                ts(m.attendanceCheckedAt()), m.proxyAttendanceReason(), m.paused(), ts(m.pausedChangedAt()), start, startReceived, start,
                target.id(), m.participantId());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO patrol_members (program_id, patrol_id, participant_id, user_id_snapshot, name_snapshot, seat_no, activity_date,
                        scheduled_range, attendance, attendance_requested_at, attendance_request_received_at, attendance_checked_by,
                        attendance_checked_at, proxy_attendance_reason, started_at, start_received_at, start_source, paused, paused_changed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, tstzrange(?, ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    target.programId(), target.id(), m.participantId(), m.userId(), m.name(), seat, target.activityDate(),
                    Timestamp.from(target.scheduledStartAt()), Timestamp.from(target.expectedReturnAt()), m.attendance(),
                    ts(m.attendanceRequestedAt()), ts(m.attendanceRequestReceivedAt()), m.attendanceCheckedBy(), ts(m.attendanceCheckedAt()),
                    m.proxyAttendanceReason(), start, startReceived, start == null ? null : "CARRIED", m.paused(), ts(m.pausedChangedAt()));
        }
    }

    private int freeSeat(UUID patrolId) {
        Set<Integer> taken = new TreeSet<>(jdbc.queryForList(
                "SELECT seat_no::int FROM patrol_members WHERE patrol_id = ? AND assignment_active", Integer.class, patrolId));
        int seat = 1;
        while (taken.contains(seat)) {
            seat++;
        }
        return seat;
    }

    private void requireVersion(PatrolRow patrol, long expected, String which) {
        if (patrol.version() != expected) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("currentVersion", patrol.version());
            details.put("which", which);
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.", details);
        }
    }

    /** 현장 조원: 배정 중·결석 아님·복귀 확인 전(정원 기준, 설계안 4.2). */
    static int inField(List<MemberRow> members) {
        return (int) members.stream().filter(MemberRow::inField).count();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
