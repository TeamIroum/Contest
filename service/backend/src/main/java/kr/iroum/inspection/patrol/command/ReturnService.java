package kr.iroum.inspection.patrol.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import kr.iroum.inspection.common.error.ErrorResponse;
import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.patrol.PatrolReader.MemberRow;
import kr.iroum.inspection.patrol.PatrolReader.PatrolRow;
import kr.iroum.inspection.patrol.PatrolViews.Actor;

/**
 * 앱 복귀 요청과 담당자 복귀 확인(업무 API 6.5·8.2절, D-24, D-30, D-32, D-33, D-37).
 * 정상·중도 복귀는 개인별이다. 앱 요청은 요청한 본인 행에만 최초 값을 남기고, 담당자 확인은 조원마다 구분을 저장한다.
 * 한 사람의 요청·확인이 다른 조원의 구분이나 활동을 정하지 않으며, 조 상태는 판정 함수(PatrolCommandSupport.evaluate)가 정한다.
 * 시작 시각은 만들지 않는다. 종료된 조의 누락 확인은 저장하되 활동을 다시 열지 않는다.
 */
@Service
public class ReturnService {

    /** 앱 복귀 요청 결과. returnReason·abortReason은 요청한 본인의 최초 요청값이다. */
    public record ReturnResult(UUID patrolId, String activityStatus, String returnReason, String abortReason, boolean duplicate,
            long patrolRevision) {

        ReturnResult asDuplicate() {
            return new ReturnResult(patrolId, activityStatus, returnReason, abortReason, true, patrolRevision);
        }
    }

    /** 복귀 확인 결과. returnReason·abortReason은 그 조원의 확인 구분, alreadyConfirmed는 이번 요청이 아무것도 저장하지 않았음을 뜻한다. */
    public record ReturnConfirmationResult(UUID patrolId, UUID participantId, Instant returnedAt, String returnSource,
            Actor returnConfirmedBy, long version, String activityStatus, long patrolRevision, boolean returned, Instant returnConfirmedAt,
            String returnReason, String abortReason, boolean alreadyConfirmed) {
    }

    private final PatrolCommandSupport support;
    private final AccessPolicy access;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    public ReturnService(PatrolCommandSupport support, AccessPolicy access, IdempotencyService idempotency, JdbcTemplate jdbc,
            AuditLog audit) {
        this.support = support;
        this.access = access;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public ReturnResult request(AuthenticatedUser user, UUID patrolId, String returnReason, String abortReason, UUID clientEventId,
            Instant occurredAt, UUID assignmentRef) {
        requireReasonPair(returnReason, abortReason);
        AccessPolicy.SeniorAssignment assignment = access.requireSeniorParticipant(user, patrolId);
        UUID participantId = assignment.participantId();
        PatrolRow patrol = support.lock(patrolId);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("type", "RETURN_REQUESTED");
        identity.put("patrolId", patrolId);
        identity.put("returnReason", returnReason);
        identity.put("abortReason", abortReason);
        identity.put("occurredAt", occurredAt.toString());
        String code = abortReason == null ? returnReason : returnReason + ":" + abortReason;
        // 멱등 재전송 판정이 상태 검사보다 먼저다(D-32). 거절되면 롤백으로 이벤트도 남지 않는다.
        var outcome = support.event(user.userId(), patrol, null, "RETURN_REQUESTED", code, clientEventId, occurredAt, identity,
                assignmentRef, assignment.memberId(), ReturnResult.class, () -> {
                    support.requireNotCancelled(patrol);
                    support.requireActivityTime(patrol, occurredAt);
                    if ("ASSIGNED".equals(patrol.status())) {
                        throw support.stateConflict("NOT_STARTED", patrol, "아직 활동을 시작하지 않았어요. 시작 전에는 복귀 요청을 보낼 수 없어요.");
                    }
                    UUID participation = support.resolveParticipation(patrol, assignment, user.userId(), clientEventId, assignmentRef,
                            occurredAt);
                    if (!assignment.active() || !support.isOpenParticipation(participation)) {
                        // 이동 전(또는 같은 조의 이전 참여 구간, A→B→A)에 발생한 복귀 요청: 이력만 남긴다.
                        // 지금 참여 구간의 개인 복귀 요청·조 상태를 바꾸지 않고, 이동은 복귀가 아니므로 새 조에도 만들지 않는다(설계안 4.7).
                        MemberRow old = support.memberRow(patrolId, participantId);
                        return new ReturnResult(patrolId, patrol.status(), requested(old, returnReason), requestedAbort(old, abortReason), false,
                                patrol.revision());
                    }
                    MemberRow me = support.member(patrolId, participantId);
                    if (patrol.ended() || me.returned() || me.requestedReturnReason() != null) {
                        // 늦거나 다시 보낸 요청은 이력만 남기고 최초 요청값·확인값·종료 상태를 바꾸지 않는다(7절, D-38).
                        return new ReturnResult(patrolId, patrol.status(), requested(me, returnReason), requestedAbort(me, abortReason),
                                false, patrol.revision());
                    }
                    jdbc.update("""
                            UPDATE patrol_members SET requested_return_reason = ?, requested_abort_reason = ?, return_requested_at = ?,
                                return_request_received_at = ?, updated_at = now()
                            WHERE patrol_id = ? AND participant_id = ? AND assignment_active""",
                            returnReason, abortReason, Timestamp.from(occurredAt), Timestamp.from(support.now()), patrolId, participantId);
                    support.evaluate(patrolId, user.userId(), null, participantId);
                    long revision = support.bumpRevision(patrolId);
                    return new ReturnResult(patrolId, support.reload(patrolId).status(), returnReason, abortReason, false, revision);
                });
        return outcome.replayed() ? outcome.result().asDuplicate() : outcome.result();
    }

    @Transactional
    public IdempotencyService.Outcome<ReturnConfirmationResult> confirm(AuthenticatedUser user, UUID patrolId, UUID participantId,
            Instant returnedAt, String returnReason, String abortReason, long version, UUID key) {
        requireReasonPair(returnReason, abortReason);
        UUID programId = access.requireManagedPatrol(user, patrolId);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("patrolId", patrolId);
        identity.put("participantId", participantId);
        identity.put("returnedAt", returnedAt.toString());
        identity.put("returnReason", returnReason);
        identity.put("abortReason", abortReason);
        identity.put("version", version);
        return idempotency.execute(user.userId(), "POST /admin/patrols/{id}/return-confirmations", key, identity,
                ReturnConfirmationResult.class, 200, () -> {
                    PatrolRow patrol = support.lock(patrolId);
                    support.requireVersion(patrol, version);
                    support.requireNotCancelled(patrol);
                    MemberRow member = support.activeMember(patrol, participantId);
                    if (member.absent()) {
                        throw support.stateConflict("MEMBER_ABSENT", patrol, "결석으로 확정된 조원은 복귀 확인 대상이 아니에요.");
                    }
                    if (member.returned()) {
                        // 같은 구분이면 저장 없이 현재 값을 돌려주고(alreadyConfirmed), 다른 구분이면 저장하지 않았음을 409로 알린다.
                        if (returnReason.equals(member.returnReason()) && java.util.Objects.equals(abortReason, member.abortReason())) {
                            return result(patrol, member, true);
                        }
                        throw support.stateConflict("RETURN_ALREADY_CONFIRMED", patrol, "이미 다른 구분으로 복귀를 확인한 조원이에요.");
                    }
                    support.requireActivityTime(patrol, returnedAt);
                    if (member.startedAt() != null && returnedAt.isBefore(member.startedAt())) {
                        throw new ApiException(ErrorCode.INVALID_ACTIVITY_TIME, "복귀 시각이 시작 시각보다 빨라요.");
                    }
                    Instant now = support.now();
                    // 이미 정정된 실제 복귀 시각은 확인으로 덮어쓰지 않는다(8.2절).
                    boolean corrected = "ADMIN_CORRECTION".equals(member.returnSource());
                    jdbc.update("""
                            UPDATE patrol_members SET returned_at = ?, return_source = ?, return_confirmed_at = ?, return_confirmed_by = ?,
                                return_reason = ?, abort_reason = ?, version = version + 1, updated_at = now()
                            WHERE patrol_id = ? AND participant_id = ? AND assignment_active""",
                            Timestamp.from(corrected ? member.returnedAt() : returnedAt), corrected ? "ADMIN_CORRECTION" : "MANAGER_CONFIRMATION",
                            Timestamp.from(now), user.userId(), returnReason, abortReason, patrolId, participantId);
                    if (member.requestedReturnReason() != null && (!returnReason.equals(member.requestedReturnReason())
                            || !java.util.Objects.equals(abortReason, member.requestedAbortReason()))) {
                        // 본인 요청과 다른 확인: 요청값은 행·이벤트에 그대로 두고 확인 사실을 감사한다(D-33).
                        Map<String, Object> before = new LinkedHashMap<>();
                        before.put("requestedReturnReason", member.requestedReturnReason());
                        before.put("requestedAbortReason", member.requestedAbortReason());
                        Map<String, Object> after = new LinkedHashMap<>();
                        after.put("returnReason", returnReason);
                        after.put("abortReason", abortReason);
                        audit.record(programId, user.userId(), "RETURN_CONFIRMED_OVER_REQUEST", "PATROL_MEMBER", participantId, null, before,
                                after);
                    }
                    support.evaluate(patrolId, user.userId(), PatrolCommandSupport.EndTrigger.LAST_RETURN_CONFIRMED, participantId);
                    support.bumpVersion(patrolId);
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("returnedAt", (corrected ? member.returnedAt() : returnedAt).toString());
                    after.put("returnReason", returnReason);
                    after.put("abortReason", abortReason);
                    after.put("afterEnd", patrol.ended());
                    audit.record(programId, user.userId(), "RETURN_CONFIRMED", "PATROL_MEMBER", participantId, null, null, after);
                    return result(support.reload(patrolId), support.member(patrolId, participantId), false);
                });
    }

    private static String requested(MemberRow me, String sent) {
        return me.requestedReturnReason() != null ? me.requestedReturnReason() : sent;
    }

    private static String requestedAbort(MemberRow me, String sent) {
        return me.requestedReturnReason() != null ? me.requestedAbortReason() : sent;
    }

    private static void requireReasonPair(String returnReason, String abortReason) {
        boolean abort = "ABORT".equals(returnReason);
        if (abort != (abortReason != null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, List.of(new ErrorResponse.FieldError(
                    "abortReason", abort ? "REQUIRED" : "INVALID_FORMAT", "중도 복귀일 때만 사유를 보냅니다.")));
        }
    }

    private static ReturnConfirmationResult result(PatrolRow patrol, MemberRow m, boolean alreadyConfirmed) {
        Actor by = m.returnConfirmedBy() == null ? null : new Actor(m.returnConfirmedBy(), m.returnConfirmedByName());
        return new ReturnConfirmationResult(patrol.id(), m.participantId(), m.returnedAt(), m.returnSource(), by, patrol.version(),
                patrol.status(), patrol.revision(), m.returned(), m.returnConfirmedAt(), m.returnReason(), m.abortReason(), alreadyConfirmed);
    }
}
