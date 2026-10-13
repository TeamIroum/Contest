package kr.iroum.inspection.patrol.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.patrol.PatrolReader.MemberRow;
import kr.iroum.inspection.patrol.PatrolReader.PatrolRow;
import kr.iroum.inspection.patrol.PatrolViews.Actor;

/**
 * 개인 시작과 담당자 출발 확인(D-02, 업무 API 6.1·8.1절). 둘은 서로 기다리지 않는 독립 기록이다.
 * 한 사람의 시작이 다른 조원의 시각을 만들지 않고, 출발 확인은 활동 상태를 바꾸지 않는다.
 * 참여 가능 인원이 1명 이하이면 둘 다 거절한다(D-30). 출발 확인 재호출은 최초 값을 유지한다(D-31).
 */
@Service
public class StartDepartureService {

    public record ActivityStartResult(UUID patrolId, UUID participantId, Instant startedAt, Instant startReceivedAt, String startSource,
            String activityStatus, Instant departedAt, boolean duplicate, long patrolRevision) {

        ActivityStartResult asDuplicate() {
            return new ActivityStartResult(patrolId, participantId, startedAt, startReceivedAt, startSource, activityStatus, departedAt,
                    true, patrolRevision);
        }
    }

    public record DepartureResult(UUID patrolId, Instant departureConfirmedAt, Actor departureConfirmedBy, long version,
            String activityStatus, long patrolRevision) {
    }

    private final PatrolCommandSupport support;
    private final kr.iroum.inspection.patrol.PatrolReader reader;
    private final AccessPolicy access;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    public StartDepartureService(PatrolCommandSupport support, kr.iroum.inspection.patrol.PatrolReader reader, AccessPolicy access,
            IdempotencyService idempotency, JdbcTemplate jdbc, AuditLog audit) {
        this.support = support;
        this.reader = reader;
        this.access = access;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public ActivityStartResult start(AuthenticatedUser user, UUID patrolId, UUID clientEventId, Instant occurredAt, UUID assignmentRef) {
        AccessPolicy.SeniorAssignment assignment = access.requireSeniorParticipant(user, patrolId);
        UUID participantId = assignment.participantId();
        // 이동 전에 발생한 시작이 늦게 오면 지금 조의 사본(CARRIED)도 채우므로 그 조까지 id 순서로 잠근다(설계안 4.7).
        PatrolRow seen = reader.patrol(patrolId).orElseThrow();
        UUID current = assignment.active() ? patrolId : reader.currentPatrolOf(participantId, seen.activityDate()).orElse(null);
        java.util.TreeSet<UUID> lockIds = new java.util.TreeSet<>(java.util.Set.of(patrolId));
        if (current != null) {
            lockIds.add(current);
        }
        Map<UUID, PatrolRow> locked = new java.util.LinkedHashMap<>();
        for (UUID id : lockIds) {
            locked.put(id, support.lock(id));
        }
        PatrolRow patrol = locked.get(patrolId);
        var outcome = support.event(user.userId(), patrol, null, "ACTIVITY_STARTED", null, clientEventId, occurredAt,
                Map.of("type", "ACTIVITY_STARTED", "patrolId", patrolId, "occurredAt", occurredAt.toString()), assignmentRef,
                assignment.memberId(), ActivityStartResult.class, () -> {
                    support.requireNotCancelled(patrol);
                    support.requireActivityTime(patrol, occurredAt);
                    support.resolveParticipation(patrol, assignment, user.userId(), clientEventId, assignmentRef, occurredAt);
                    if (!assignment.active()) {
                        return lateStartBeforeTransfer(patrol, participantId, current, occurredAt);
                    }
                    List<MemberRow> members = support.members(patrolId);
                    MemberRow me = members.stream().filter(m -> m.participantId().equals(participantId)).findFirst().orElseThrow();
                    if (me.absent()) {
                        throw support.stateConflict("MEMBER_ABSENT", patrol, "결석으로 처리되어 시작할 수 없어요. 활동 담당자에게 확인해 주세요.");
                    }
                    if (me.startedAt() != null) {
                        // 본인의 중복 시작 클릭은 최초 시각을 유지하고 이력만 남긴다.
                        return result(patrol, me, true, patrol.revision());
                    }
                    if (patrol.ended() || me.returned()) {
                        // 종료·복귀 확인 뒤 늦게 온 시작은 이력만 남기고 확정값을 바꾸지 않는다(업무 API 7절).
                        return result(patrol, me, false, patrol.revision());
                    }
                    support.requireNotSolo(patrol, members);
                    if (me.returnedAt() != null && occurredAt.isAfter(me.returnedAt())) {
                        throw new ApiException(ErrorCode.INVALID_ACTIVITY_TIME, "시작 시각이 복귀 시각보다 늦어요.");
                    }
                    Instant received = support.now();
                    jdbc.update("""
                            UPDATE patrol_members SET started_at = ?, start_received_at = ?, start_source = 'SELF', updated_at = now()
                            WHERE patrol_id = ? AND participant_id = ? AND assignment_active""",
                            Timestamp.from(occurredAt), Timestamp.from(received), patrolId, participantId);
                    support.evaluate(patrolId, user.userId(), null, participantId);
                    support.recomputeDepartedAt(patrolId);
                    long revision = support.bumpRevision(patrolId);
                    PatrolRow updated = support.reload(patrolId);
                    return new ActivityStartResult(patrolId, participantId, occurredAt, received, "SELF", updated.status(),
                            updated.departedAt(), false, revision);
                });
        return outcome.replayed() ? outcome.result().asDuplicate() : outcome.result();
    }

    @Transactional
    public IdempotencyService.Outcome<DepartureResult> confirmDeparture(AuthenticatedUser user, UUID patrolId, long version, UUID key) {
        UUID programId = access.requireManagedPatrol(user, patrolId);
        return idempotency.execute(user.userId(), "POST /admin/patrols/{id}/departure-confirmations", key,
                Map.of("patrolId", patrolId, "version", version), DepartureResult.class, 200, () -> {
                    PatrolRow patrol = support.lock(patrolId);
                    support.requireVersion(patrol, version);
                    support.requireNotCancelled(patrol);
                    if (patrol.departureConfirmedAt() != null) {
                        // D-31: 새 키로 다시 확인해도 최초 확인자·시각을 유지한다. version은 그대로다.
                        audit.record(programId, user.userId(), "DEPARTURE_RECONFIRM_IGNORED", "PATROL", patrolId, null, null, null);
                        return departure(patrol);
                    }
                    if (!"ASSIGNED".equals(patrol.status()) && !"IN_PROGRESS".equals(patrol.status())) {
                        // 출발 확인은 ASSIGNED/IN_PROGRESS에서만 기록한다(8.1절). 그 밖은 기록 없이 현재 값을 돌려준다.
                        return departure(patrol);
                    }
                    support.requireNotSolo(patrol, support.members(patrolId));
                    Instant now = support.now();
                    jdbc.update("UPDATE patrols SET departure_confirmed_at = ?, departure_confirmed_by = ? WHERE id = ?",
                            Timestamp.from(now), user.userId(), patrolId);
                    support.bumpVersion(patrolId);
                    audit.record(programId, user.userId(), "DEPARTURE_CONFIRMED", "PATROL", patrolId, null, null,
                            Map.of("departureConfirmedAt", now.toString()));
                    return departure(support.reload(patrolId));
                });
    }

    /**
     * 이동 전에 발생한 시작이 늦게 도착함(D-36·D-38): 이전 조 행에 시작이 없을 때만 SELF로 남기고, 지금 조의 행이 비어 있으면 CARRIED로 채운다.
     * 이미 있는 시작(최초 값)과 지금 조에서 직접 누른 시작은 덮어쓰지 않는다. 이전 조의 상태는 바꾸지 않는다.
     */
    private ActivityStartResult lateStartBeforeTransfer(PatrolRow patrol, UUID participantId, UUID current, Instant occurredAt) {
        MemberRow old = reader.memberRow(patrol.id(), participantId).orElseThrow();
        if (old.startedAt() != null) {
            return result(patrol, old, false, patrol.revision());
        }
        Instant received = support.now();
        jdbc.update("""
                UPDATE patrol_members SET started_at = ?, start_received_at = ?, start_source = 'SELF', updated_at = now()
                WHERE patrol_id = ? AND participant_id = ?""", Timestamp.from(occurredAt), Timestamp.from(received), patrol.id(), participantId);
        support.recomputeDepartedAt(patrol.id());
        if (current != null && jdbc.update("""
                UPDATE patrol_members SET started_at = ?, start_received_at = ?, start_source = 'CARRIED', updated_at = now()
                WHERE patrol_id = ? AND participant_id = ? AND started_at IS NULL""",
                Timestamp.from(occurredAt), Timestamp.from(received), current, participantId) > 0) {
            support.bumpRevision(current);
        }
        long revision = support.bumpRevision(patrol.id());
        PatrolRow updated = support.reload(patrol.id());
        return new ActivityStartResult(patrol.id(), participantId, occurredAt, received, "SELF", updated.status(), updated.departedAt(), false,
                revision);
    }

    private static DepartureResult departure(PatrolRow p) {
        Actor by = p.departureConfirmedBy() == null ? null : new Actor(p.departureConfirmedBy(), p.departureConfirmedByName());
        return new DepartureResult(p.id(), p.departureConfirmedAt(), by, p.version(), p.status(), p.revision());
    }

    private static ActivityStartResult result(PatrolRow patrol, MemberRow me, boolean duplicate, long revision) {
        return new ActivityStartResult(patrol.id(), me.participantId(), me.startedAt(), me.startReceivedAt(), me.startSource(),
                patrol.status(), patrol.departedAt(), duplicate, revision);
    }
}
