package kr.iroum.inspection.patrol.command;

import java.sql.Timestamp;
import java.time.Instant;
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
import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.patrol.PatrolReader.MemberRow;
import kr.iroum.inspection.patrol.PatrolReader.PatrolRow;
import kr.iroum.inspection.patrol.PatrolViews.Actor;

/**
 * 개인 시작·복귀 시각 입력·정정(8.3절)과 실제 활동 없는 해산(8.4절).
 * 정정은 복귀 확인·출석·출발 확인을 만들지 않고 종료 상태를 다시 열지 않는다. 원본 이벤트는 보존하고 변경 전후를 감사한다.
 */
@Service
public class CorrectionCancellationService {

    public record TimeCorrectionResult(UUID correctionId, UUID patrolId, UUID participantId, Instant startedAt, String startSource,
            Instant returnedAt, String returnSource, long version, String activityStatus, long patrolRevision, boolean returned,
            Instant returnConfirmedAt) {
    }

    public record CancellationResult(UUID patrolId, String activityStatus, Instant cancelledAt, Actor cancelledBy,
            String cancellationReason, long version, long patrolRevision) {
    }

    private final PatrolCommandSupport support;
    private final AccessPolicy access;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    public CorrectionCancellationService(PatrolCommandSupport support, AccessPolicy access, IdempotencyService idempotency,
            JdbcTemplate jdbc, AuditLog audit) {
        this.support = support;
        this.access = access;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public IdempotencyService.Outcome<TimeCorrectionResult> correct(AuthenticatedUser user, UUID patrolId, UUID participantId,
            Instant startedAt, Instant returnedAt, String reason, long version, UUID key) {
        UUID programId = access.requireManagedPatrol(user, patrolId);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("patrolId", patrolId);
        identity.put("participantId", participantId);
        identity.put("startedAt", startedAt == null ? null : startedAt.toString());
        identity.put("returnedAt", returnedAt == null ? null : returnedAt.toString());
        identity.put("reason", reason);
        identity.put("version", version);
        return idempotency.execute(user.userId(), "POST /admin/patrols/{id}/members/{participantId}/time-corrections", key, identity,
                TimeCorrectionResult.class, 201, () -> {
                    // 개인 시작은 하나의 사실이다(D-36): 이동으로 이어받은 사본(CARRIED)이 있는 조까지 id 순서로 잠근다(설계안 4.2).
                    Set<UUID> related = new TreeSet<>(startRelatedPatrols(patrolId, participantId));
                    related.add(patrolId);
                    Map<UUID, PatrolRow> locked = new java.util.LinkedHashMap<>();
                    for (UUID id : related) {
                        locked.put(id, support.lock(id));
                    }
                    PatrolRow patrol = locked.get(patrolId);
                    support.requireVersion(patrol, version);
                    support.requireNotCancelled(patrol);
                    MemberRow member = support.activeMember(patrol, participantId);
                    if (member.absent()) {
                        throw support.stateConflict("MEMBER_ABSENT", patrol, "결석으로 확정된 조원이에요. 출결을 먼저 확인해 주세요.");
                    }
                    support.requireActivityTime(patrol, startedAt);
                    support.requireActivityTime(patrol, returnedAt);
                    Instant finalStart = startedAt != null ? startedAt : member.startedAt();
                    Instant finalReturn = returnedAt != null ? returnedAt : member.returnedAt();
                    if (finalStart != null && finalReturn != null && finalStart.isAfter(finalReturn)) {
                        throw new ApiException(ErrorCode.INVALID_ACTIVITY_TIME, "시작 시각이 복귀 시각보다 늦어요.");
                    }
                    boolean carried = "CARRIED".equals(member.startSource());
                    // 이어받은 시작을 정정하면 원래 기록한 조의 행이 정정 출처가 되고 이 행은 CARRIED로 남는다.
                    String startSource = startedAt == null ? member.startSource() : carried ? "CARRIED" : "ADMIN_CORRECTION";
                    String returnSource = returnedAt != null ? "ADMIN_CORRECTION" : member.returnSource();
                    jdbc.update("""
                            UPDATE patrol_members SET started_at = ?, start_source = ?, returned_at = ?, return_source = ?,
                                version = version + 1, updated_at = now()
                            WHERE patrol_id = ? AND participant_id = ? AND assignment_active""",
                            ts(finalStart), startSource, ts(finalReturn), returnSource, patrolId, participantId);
                    // 다른 조에서 실제로 바뀐 행이 있으면 그 조의 version·revision도 같은 트랜잭션에서 올린다(관리자 화면 낙관적 잠금·5초 조회).
                    Set<UUID> propagated = new TreeSet<>();
                    if (startedAt != null) {
                        if (carried) {
                            propagated.addAll(jdbc.queryForList("""
                                    UPDATE patrol_members SET started_at = ?, start_source = 'ADMIN_CORRECTION', version = version + 1, updated_at = now()
                                    WHERE participant_id = ? AND activity_date = ? AND start_source IN ('SELF', 'ADMIN_CORRECTION')
                                      AND started_at = ? AND patrol_id <> ?
                                    RETURNING patrol_id""",
                                    UUID.class, ts(startedAt), participantId, patrol.activityDate(), ts(member.startedAt()), patrolId));
                        }
                        propagated.addAll(jdbc.queryForList("""
                                UPDATE patrol_members SET started_at = ?, version = version + 1, updated_at = now()
                                WHERE participant_id = ? AND activity_date = ? AND start_source = 'CARRIED' AND patrol_id <> ?
                                  AND started_at IS DISTINCT FROM ?
                                RETURNING patrol_id""",
                                UUID.class, ts(startedAt), participantId, patrol.activityDate(), patrolId, ts(startedAt)));
                    }
                    // 첫 유효 시작 입력은 조를 활동 중으로 바꾼다. 정정은 현장 조원을 바꾸지 않으므로 종료를 일으키지 않는다.
                    support.evaluate(patrolId, user.userId(), null, participantId);
                    for (UUID id : related) {
                        support.recomputeDepartedAt(id);
                    }
                    for (UUID id : propagated) {
                        // 잠금 집합(related) 밖의 조는 없다: 시작 기록이 있는 조를 미리 모두 잠갔다. 종료 기록(patrol_end_records)은 건드리지 않는다.
                        support.bumpVersion(id);
                    }
                    UUID correctionId = jdbc.queryForObject("""
                            INSERT INTO patrol_time_corrections (program_id, patrol_id, participant_id, before_started_at, after_started_at,
                                before_returned_at, after_returned_at, reason, actor_user_id, recorded_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
                            patrol.programId(), patrolId, participantId, ts(member.startedAt()), ts(startedAt), ts(member.returnedAt()),
                            ts(returnedAt), reason, user.userId(), Timestamp.from(support.now()));
                    support.bumpVersion(patrolId);
                    Map<String, Object> before = new LinkedHashMap<>();
                    before.put("startedAt", str(member.startedAt()));
                    before.put("returnedAt", str(member.returnedAt()));
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("startedAt", str(finalStart));
                    after.put("returnedAt", str(finalReturn));
                    after.put("propagatedPatrolIds", propagated.stream().map(UUID::toString).toList());
                    audit.record(programId, user.userId(), "ACTIVITY_TIME_CORRECTED", "PATROL_MEMBER", participantId, reason, before, after);
                    PatrolRow updated = support.reload(patrolId);
                    MemberRow m = support.member(patrolId, participantId);
                    return new TimeCorrectionResult(correctionId, patrolId, participantId, m.startedAt(), m.startSource(), m.returnedAt(),
                            m.returnSource(), updated.version(), updated.status(), updated.revision(), m.returned(), m.returnConfirmedAt());
                });
    }

    @Transactional
    public IdempotencyService.Outcome<CancellationResult> cancel(AuthenticatedUser user, UUID patrolId, String reason, long version,
            UUID key) {
        UUID programId = access.requireManagedPatrol(user, patrolId);
        return idempotency.execute(user.userId(), "POST /admin/patrols/{id}/cancellations", key,
                Map.of("patrolId", patrolId, "reason", reason, "version", version), CancellationResult.class, 200, () -> {
                    PatrolRow patrol = support.lock(patrolId);
                    support.requireVersion(patrol, version);
                    support.requireNotCancelled(patrol);
                    if (!"ASSIGNED".equals(patrol.status()) || hasActivity(patrolId)) {
                        throw new ApiException(ErrorCode.ACTIVITY_ALREADY_RECORDED, "활동 기록이 있어 해산할 수 없어요. 중도 복귀 절차를 써 주세요.");
                    }
                    Instant now = support.now();
                    // 출석 요청·확정·출발 확인·이력은 그대로 둔다. 시작·복귀 시각은 만들지 않는다.
                    jdbc.update("""
                            UPDATE patrols SET activity_status = 'CANCELLED', cancelled_at = ?, cancelled_by = ?, cancellation_reason = ?,
                                paused = false WHERE id = ?""", Timestamp.from(now), user.userId(), reason, patrolId);
                    support.bumpVersion(patrolId);
                    audit.record(programId, user.userId(), "PATROL_CANCELLED", "PATROL", patrolId, reason, null, Map.of("activityStatus",
                            "CANCELLED"));
                    PatrolRow updated = support.reload(patrolId);
                    return new CancellationResult(patrolId, updated.status(), updated.cancelledAt(),
                            new Actor(updated.cancelledBy(), updated.cancelledByName()), updated.cancellationReason(), updated.version(),
                            updated.revision());
                });
    }

    /** 실제 활동 기록: 개인 시작·복귀, 장소 처리, 사진 시도, 시작·건너뛰기·쉬기·복귀 이벤트. 출석 요청은 활동이 아니다. */
    private boolean hasActivity(UUID patrolId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM patrol_members WHERE patrol_id = ? AND assignment_active
                        AND (started_at IS NOT NULL OR returned_at IS NOT NULL OR return_confirmed_at IS NOT NULL))
                    OR EXISTS (SELECT 1 FROM patrol_stops WHERE patrol_id = ? AND visit_status <> 'PENDING')
                    OR EXISTS (SELECT 1 FROM inspections i JOIN capture_slots s ON s.id = i.capture_slot_id
                        JOIN patrol_stops ps ON ps.id = s.patrol_stop_id WHERE ps.patrol_id = ?)
                    OR EXISTS (SELECT 1 FROM patrol_events WHERE patrol_id = ? AND type <> 'ATTENDANCE_REQUESTED')""",
                Boolean.class, patrolId, patrolId, patrolId, patrolId));
    }

    /** 같은 활동일에 이 참여자의 개인 시작 기록이 있는 다른 조(원래 기록 행 또는 이어받은 사본). */
    private List<UUID> startRelatedPatrols(UUID patrolId, UUID participantId) {
        return jdbc.queryForList("""
                SELECT DISTINCT m.patrol_id FROM patrol_members m
                JOIN patrol_members cur ON cur.patrol_id = ? AND cur.participant_id = m.participant_id
                WHERE m.participant_id = ? AND m.activity_date = cur.activity_date AND m.started_at IS NOT NULL AND m.patrol_id <> ?""",
                UUID.class, patrolId, participantId, patrolId);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String str(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
