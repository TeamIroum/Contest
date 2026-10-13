package kr.iroum.inspection.patrol.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.patrol.PatrolReader;
import kr.iroum.inspection.patrol.PatrolReader.MemberRow;
import kr.iroum.inspection.patrol.PatrolReader.PatrolRow;
import kr.iroum.inspection.patrol.PatrolReader.SlotRow;
import kr.iroum.inspection.patrol.PatrolReader.StopRow;

/**
 * 장소 건너뛰기·쉬기(업무 API 6.5·7절, D-35·D-38, 설계안 5.3·5.4). 구도 완료·건너뛰기는 사진·AI 처리 상태와 분리한다.
 * 건너뛰기는 촬영 성공으로 세지 않고 시설 대장을 바꾸지 않는다.
 * <ul>
 * <li>늦게 도착한 건너뛰기는 발생 시각으로 판정한다: 본인 실제 복귀(returnedAt) 뒤에 발생했으면 409 MEMBER_RETURNED, 대기 구간 안에서
 * 발생했으면 409 SOLO_WAITING(둘 다 이벤트 없음), 그 전에 발생했으면 이력으로 남긴다.</li>
 * <li>채택 사진·완료 활동·종료 조는 되돌리지 않는다. 자동 미방문(NOT_VISITED)은 바꾸지 않아 늦은 사진 보완을 막지 않는다.</li>
 * <li>사진 처리 중인 구도가 있으면 장소를 바로 바꾸지 않고 보류한다(skipPending). 처리 결과는 04단계 사진 흐름이 정한다.</li>
 * <li>쉬기는 개인 상태 기록이다. 대기 중에도 허용하며 대기·점검 허용 여부에 영향이 없다. 현재 값은 발생 시각이 같거나 늦을 때만 바꾼다(같으면 나중 도착).</li>
 * </ul>
 */
@Service
public class StopEventService {

    /** stopUpdated: 이 요청이 장소 상태를 실제로 바꿨는지(이력만 남았으면 false). */
    public record SkipResult(UUID patrolId, UUID stopId, String visitStatus, boolean stopUpdated, boolean duplicate, long patrolRevision,
            String nextAction, UUID nextStopId, UUID nextCaptureSlotId) {

        SkipResult asDuplicate() {
            return new SkipResult(patrolId, stopId, visitStatus, stopUpdated, true, patrolRevision, nextAction, nextStopId, nextCaptureSlotId);
        }
    }

    /** paused: 요청한 조원의 현재 쉬기 상태(개인). */
    public record PauseResult(UUID patrolId, String type, Instant occurredAt, boolean paused, boolean duplicate, long patrolRevision) {

        PauseResult asDuplicate() {
            return new PauseResult(patrolId, type, occurredAt, paused, true, patrolRevision);
        }
    }

    private final PatrolCommandSupport support;
    private final PatrolReader reader;
    private final AccessPolicy access;
    private final JdbcTemplate jdbc;

    public StopEventService(PatrolCommandSupport support, PatrolReader reader, AccessPolicy access, JdbcTemplate jdbc) {
        this.support = support;
        this.reader = reader;
        this.access = access;
        this.jdbc = jdbc;
    }

    @Transactional
    public SkipResult skip(AuthenticatedUser user, UUID patrolId, UUID stopId, String reason, UUID clientEventId, Instant occurredAt,
            UUID assignmentRef) {
        AccessPolicy.SeniorAssignment assignment = access.requireSeniorParticipant(user, patrolId);
        PatrolRow patrol = support.lock(patrolId);
        if (reader.stops(patrolId).stream().noneMatch(s -> s.id().equals(stopId))) {
            throw new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("type", "STOP_SKIPPED");
        identity.put("patrolId", patrolId);
        identity.put("stopId", stopId);
        identity.put("reason", reason);
        identity.put("occurredAt", occurredAt.toString());
        var outcome = support.event(user.userId(), patrol, stopId, "STOP_SKIPPED", reason, clientEventId, occurredAt, identity, assignmentRef,
                assignment.memberId(), SkipResult.class, () -> {
                    support.requireNotCancelled(patrol);
                    support.requireActivityTime(patrol, occurredAt);
                    support.resolveParticipation(patrol, assignment, user.userId(), clientEventId, assignmentRef, occurredAt);
                    MemberRow sender = support.memberRow(patrolId, assignment.participantId());
                    if (sender.returnedAt() != null && occurredAt.isAfter(sender.returnedAt())) {
                        // 본인 실제 복귀 뒤에 발생한 새 건너뛰기는 받지 않는다(사진의 D-27 복귀 시각 기준과 같음).
                        // 복귀 전에 발생해 늦게 도착한 건너뛰기는 아래 규칙대로 이력으로 접수한다(D-38, 업무 API 7절).
                        throw support.stateConflict("MEMBER_RETURNED", patrol, "이미 복귀한 뒤의 기록이에요. 새 점검은 보낼 수 없어요.");
                    }
                    if (support.waitingAt(patrolId, assignment.participantId(), occurredAt)) {
                        // D-35·D-38: 대기 중 새로 발생한 건너뛰기는 받지 않는다. 늦게 도착해도 발생 시각 기준이다.
                        throw support.stateConflict("SOLO_WAITING", patrol, "혼자 남아 담당자 안내를 기다리는 중이에요. 새 점검은 멈춰 주세요.");
                    }
                    StopRow stop = stop(patrolId, stopId);
                    long revision = patrol.revision();
                    boolean updated = false;
                    if (!patrol.ended() && "PENDING".equals(stop.visitStatus())) {
                        UUID eventId = jdbc.queryForObject("SELECT id FROM patrol_events WHERE actor_user_id = ? AND client_event_id = ?",
                                UUID.class, user.userId(), clientEventId);
                        if (stop.slots().stream().anyMatch(s -> s.activeInspectionId() != null)) {
                            // 처리 중인 사진이 채택되면 그 결과가 우선한다. 장소는 그대로 두고 건너뛰기를 보류한다(04단계가 끝맺음).
                            jdbc.update("UPDATE patrol_stops SET skip_pending_event_id = COALESCE(skip_pending_event_id, ?) WHERE id = ?",
                                    eventId, stopId);
                        } else {
                            jdbc.update("UPDATE patrol_stops SET visit_status = 'SKIPPED' WHERE id = ? AND visit_status = 'PENDING'", stopId);
                            // 구도를 SKIPPED로 만든 이벤트를 남긴다(D-39: 건너뛰기 전 촬영 사진의 판정 기준).
                            jdbc.update("""
                                    UPDATE capture_slots SET resolution = 'SKIPPED', skipped_by_event_id = ?, version = version + 1, updated_at = now()
                                    WHERE patrol_stop_id = ? AND resolution = 'OPEN' AND active_inspection_id IS NULL""", eventId, stopId);
                            updated = true;
                        }
                        revision = support.bumpRevision(patrolId);
                    }
                    return next(support.reload(patrolId), stop(patrolId, stopId), revision, updated, assignment);
                });
        return outcome.replayed() ? outcome.result().asDuplicate() : outcome.result();
    }

    @Transactional
    public PauseResult pause(AuthenticatedUser user, UUID patrolId, String type, UUID clientEventId, Instant occurredAt, UUID assignmentRef) {
        AccessPolicy.SeniorAssignment assignment = access.requireSeniorParticipant(user, patrolId);
        PatrolRow seen = reader.patrol(patrolId).orElseThrow();
        // 쉬기 현재 값은 지금 배정된 조의 행 하나에 둔다. 이동했으면 그 조까지 id 순서로 잠근다(설계안 4.2·5.4).
        UUID current = assignment.active() ? patrolId : reader.currentPatrolOf(assignment.participantId(), seen.activityDate()).orElse(null);
        TreeSet<UUID> lockIds = new TreeSet<>();
        lockIds.add(patrolId);
        if (current != null) {
            lockIds.add(current);
        }
        Map<UUID, PatrolRow> locked = new LinkedHashMap<>();
        for (UUID id : lockIds) {
            locked.put(id, support.lock(id));
        }
        PatrolRow patrol = locked.get(patrolId);
        Map<String, Object> identity = Map.of("type", type, "patrolId", patrolId, "occurredAt", occurredAt.toString());
        var outcome = support.event(user.userId(), patrol, null, type, null, clientEventId, occurredAt, identity, assignmentRef,
                assignment.memberId(), PauseResult.class, () -> {
                    support.requireNotCancelled(patrol);
                    support.requireActivityTime(patrol, occurredAt);
                    support.resolveParticipation(patrol, assignment, user.userId(), clientEventId, assignmentRef, occurredAt);
                    boolean wanted = "PAUSE_STARTED".equals(type);
                    PatrolRow holder = current == null ? null : locked.get(current);
                    MemberRow me = holder == null ? null : support.members(holder.id()).stream()
                            .filter(m -> m.participantId().equals(assignment.participantId())).findFirst().orElse(null);
                    boolean running = holder != null && ("IN_PROGRESS".equals(holder.status()) || "RETURNING".equals(holder.status()));
                    if (me == null || !running || me.returned()) {
                        // 시작 전·종료 뒤·복귀 확인 뒤의 쉬기는 이력만 남긴다.
                        return new PauseResult(patrolId, type, occurredAt, me != null && me.paused(), false, patrol.revision());
                    }
                    long revision = patrol.revision();
                    boolean newer = me.pausedChangedAt() == null || !occurredAt.isBefore(me.pausedChangedAt());
                    if (newer) {
                        jdbc.update("""
                                UPDATE patrol_members SET paused = ?, paused_changed_at = ?, updated_at = now()
                                WHERE patrol_id = ? AND participant_id = ?""", wanted, Timestamp.from(occurredAt), holder.id(), me.participantId());
                        if (me.paused() != wanted) {
                            long bumped = support.bumpRevision(holder.id());
                            revision = holder.id().equals(patrolId) ? bumped : revision;
                        }
                    }
                    return new PauseResult(patrolId, type, occurredAt, newer ? wanted : me.paused(), false, revision);
                });
        return outcome.replayed() ? outcome.result().asDuplicate() : outcome.result();
    }

    private StopRow stop(UUID patrolId, UUID stopId) {
        return reader.stops(patrolId).stream().filter(s -> s.id().equals(stopId)).findFirst().orElseThrow();
    }

    /**
     * 다음 안내: 지금 혼자 남아 대기 중이면 WAIT_FOR_INSTRUCTION, 이 조에서 이동해 나갔거나 복귀 중·종료면 RETURN_TO_CENTER,
     * 그 밖에는 남은 PENDING 장소의 첫 열린 구도.
     */
    private SkipResult next(PatrolRow patrol, StopRow stop, long revision, boolean updated, AccessPolicy.SeniorAssignment me) {
        if (support.openWait(patrol.id()).filter(w -> w.participantId().equals(me.participantId())).isPresent()) {
            return new SkipResult(patrol.id(), stop.id(), stop.visitStatus(), updated, false, revision, "WAIT_FOR_INSTRUCTION", null, null);
        }
        boolean field = me.active() && ("ASSIGNED".equals(patrol.status()) || "IN_PROGRESS".equals(patrol.status()));
        if (field) {
            List<StopRow> stops = reader.stops(patrol.id());
            for (StopRow candidate : stops) {
                if (!"PENDING".equals(candidate.visitStatus()) || candidate.skipPending()) {
                    continue;
                }
                for (SlotRow slot : candidate.slots()) {
                    if ("OPEN".equals(slot.resolution())) {
                        return new SkipResult(patrol.id(), stop.id(), stop.visitStatus(), updated, false, revision, "NEXT_STOP", candidate.id(),
                                slot.id());
                    }
                }
            }
        }
        return new SkipResult(patrol.id(), stop.id(), stop.visitStatus(), updated, false, revision, "RETURN_TO_CENTER", null, null);
    }
}
