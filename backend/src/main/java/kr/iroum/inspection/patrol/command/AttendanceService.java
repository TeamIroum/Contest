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
import kr.iroum.inspection.patrol.PatrolReader.MemberRow;
import kr.iroum.inspection.patrol.PatrolReader.PatrolRow;

/**
 * 출석(D-01, 업무 API 6.1·8절). GPS 없이 본인 출석 요청 → 담당자 확정. 요청 없이 PRESENT로 확정하면 대리 사유가 필요하다.
 * 확정 결석(ABSENT)을 출석으로 정정하면 출석 요청 유무와 무관하게 정정 사유가 필요하다(D-37). 저장마다 변경 이력을 남기며,
 * 종료된 조에서도 저장하되 활동을 다시 열지 않는다. 로그인·출석 요청·대리 출석은 시작 기록을 만들지 않는다.
 */
@Service
public class AttendanceService {

    public record AttendanceRequestResult(UUID patrolId, UUID participantId, String attendance, Instant attendanceRequestedAt,
            Instant attendanceRequestReceivedAt, boolean duplicate, long patrolRevision) {

        AttendanceRequestResult asDuplicate() {
            return new AttendanceRequestResult(patrolId, participantId, attendance, attendanceRequestedAt, attendanceRequestReceivedAt,
                    true, patrolRevision);
        }
    }

    /** pendingReturnConfirmation: 종료된 조에서 이 조원의 복귀 확인을 보완해야 함(정정으로 현장 조원이 됨). */
    public record AttendanceUpdateResult(UUID patrolId, UUID participantId, String attendance, Instant attendanceCheckedAt,
            String proxyAttendanceReason, String correctionReason, long version, String activityStatus, long patrolRevision,
            boolean pendingReturnConfirmation) {
    }

    private final PatrolCommandSupport support;
    private final AccessPolicy access;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    public AttendanceService(PatrolCommandSupport support, AccessPolicy access, JdbcTemplate jdbc, AuditLog audit) {
        this.support = support;
        this.access = access;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public AttendanceRequestResult request(AuthenticatedUser user, UUID patrolId, UUID clientEventId, Instant occurredAt, UUID assignmentRef) {
        AccessPolicy.SeniorAssignment assignment = access.requireSeniorParticipant(user, patrolId);
        UUID participantId = assignment.participantId();
        PatrolRow patrol = support.lock(patrolId);
        var outcome = support.event(user.userId(), patrol, null, "ATTENDANCE_REQUESTED", null, clientEventId, occurredAt,
                Map.of("type", "ATTENDANCE_REQUESTED", "patrolId", patrolId, "occurredAt", occurredAt.toString()), assignmentRef,
                assignment.memberId(), AttendanceRequestResult.class, () -> {
                    support.requireNotCancelled(patrol);
                    support.requireActivityTime(patrol, occurredAt);
                    support.resolveParticipation(patrol, assignment, user.userId(), clientEventId, assignmentRef, occurredAt);
                    if (!assignment.active()) {
                        // 이동 전에 발생한 출석 요청이 늦게 옴: 이력만 남기고 출결을 바꾸지 않는다(설계안 4.7).
                        MemberRow old = support.memberRow(patrolId, participantId);
                        return new AttendanceRequestResult(patrolId, participantId, old.attendance(), old.attendanceRequestedAt(),
                                old.attendanceRequestReceivedAt(), false, patrol.revision());
                    }
                    MemberRow me = support.member(patrolId, participantId);
                    if (me.attendanceRequestedAt() != null) {
                        // 다시 누르면 최초 요청 시각을 유지하고 이력만 남긴다.
                        return new AttendanceRequestResult(patrolId, participantId, me.attendance(), me.attendanceRequestedAt(),
                                me.attendanceRequestReceivedAt(), true, patrol.revision());
                    }
                    Instant received = support.now();
                    jdbc.update("""
                            UPDATE patrol_members SET attendance_requested_at = ?, attendance_request_received_at = ?, updated_at = now()
                            WHERE patrol_id = ? AND participant_id = ? AND assignment_active""",
                            Timestamp.from(occurredAt), Timestamp.from(received), patrolId, participantId);
                    long revision = support.bumpRevision(patrolId);
                    return new AttendanceRequestResult(patrolId, participantId, me.attendance(), occurredAt, received, false, revision);
                });
        return outcome.replayed() ? outcome.result().asDuplicate() : outcome.result();
    }

    @Transactional
    public AttendanceUpdateResult update(AuthenticatedUser user, UUID patrolId, UUID participantId, String attendance, String proxyReason,
            String correctionReason, long version) {
        UUID programId = access.requireManagedPatrol(user, patrolId);
        PatrolRow patrol = support.lock(patrolId);
        support.requireVersion(patrol, version);
        support.requireNotCancelled(patrol);
        MemberRow member = support.activeMember(patrol, participantId);
        String correction = blankToNull(correctionReason);
        String storedProxy = null;
        if ("ABSENT".equals(attendance)) {
            if (member.hasActivity()) {
                // D-30: 실제 활동한 조원은 결석으로 바꾸지 않고 정상·중도 복귀 절차로 처리한다.
                throw support.stateConflict("MEMBER_ALREADY_ACTIVE", patrol, "시작·복귀 기록이 있어 결석으로 바꿀 수 없어요.");
            }
        } else {
            if (member.absent() && correction == null) {
                // D-37: 잘못된 결석의 출석 정정은 출석 요청이 있어도 사유가 필요하다.
                throw required("correctionReason", "결석을 출석으로 정정하려면 정정 사유가 필요합니다.");
            }
            if (member.attendanceRequestedAt() == null) {
                String proxy = blankToNull(proxyReason);
                // 결석 정정이면 정정 사유가 대리 출석 사유 요건도 채운다(같은 사유를 두 번 받지 않음, 설계안 6절).
                storedProxy = proxy != null ? proxyReason : correction != null && member.absent() ? correctionReason : null;
                if (storedProxy == null) {
                    throw required("proxyReason", "출석 요청이 없으면 대리 출석 사유가 필요합니다.");
                }
            }
        }
        Instant checkedAt = support.now();
        jdbc.update("""
                UPDATE patrol_members SET attendance = ?, attendance_checked_by = ?, attendance_checked_at = ?, proxy_attendance_reason = ?,
                    version = version + 1, updated_at = now()
                WHERE patrol_id = ? AND participant_id = ? AND assignment_active""",
                attendance, user.userId(), Timestamp.from(checkedAt), storedProxy, patrolId, participantId);
        jdbc.update("""
                INSERT INTO patrol_attendance_changes (program_id, patrol_id, participant_id, before_attendance, after_attendance,
                    correction_reason, proxy_reason, actor_user_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                patrol.programId(), patrolId, participantId, member.attendance(), attendance, correction == null ? null : correctionReason,
                storedProxy, user.userId(), Timestamp.from(checkedAt));
        long newVersion = support.bumpVersion(patrolId);
        // 결석 확정으로 현장 조원이 없어지고 복귀한 사람이 있으면 끝난다. 전원 결석은 끝내지 않는다(D-30, 설계안 3.2).
        // 종료된 조의 정정은 저장만 하고 다시 열지 않는다(D-37).
        support.evaluate(patrolId, user.userId(), PatrolCommandSupport.EndTrigger.ABSENT_CONFIRMED, participantId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("attendance", member.attendance());
        before.put("proxyAttendanceReason", member.proxyAttendanceReason());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("attendance", attendance);
        after.put("proxyAttendanceReason", storedProxy);
        after.put("correctionReason", correction == null ? null : correctionReason);
        audit.record(programId, user.userId(), "ATTENDANCE_CONFIRMED", "PATROL_MEMBER", participantId,
                correction != null ? correctionReason : storedProxy, before, after);
        PatrolRow updated = support.reload(patrolId);
        MemberRow saved = support.member(patrolId, participantId);
        return new AttendanceUpdateResult(patrolId, participantId, attendance, checkedAt, storedProxy, correction == null ? null : correctionReason,
                newVersion, updated.status(), updated.revision(), updated.ended() && saved.inField());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ApiException required(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null,
                List.of(new ErrorResponse.FieldError(field, "REQUIRED", message)));
    }
}
