package kr.iroum.inspection.patrol;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 순회 한 건의 행을 읽는다. 조회는 호출자의 읽기 트랜잭션(REPEATABLE READ) 안에서, 명령은 순회 행 잠금 뒤에 쓴다.
 * 배정 행은 assignment_active만 조원으로 본다.
 */
@Repository
public class PatrolReader {

    public record PatrolRow(UUID id, UUID programId, UUID organizationId, String organizationName, UUID baseOrganizationId,
            String baseName, LocalDate activityDate, Instant scheduledStartAt, Instant expectedReturnAt, String teamName, String routeName,
            String status, Instant departedAt,
            Instant departureConfirmedAt, UUID departureConfirmedBy, String departureConfirmedByName, boolean paused,
            Instant cancelledAt, UUID cancelledBy, String cancelledByName, String cancellationReason, Instant finishedAt,
            Instant syncDeadlineAt, long revision, long version) {

        public boolean terminal() {
            return ended() || "CANCELLED".equals(status);
        }

        /** 활동 종료(ENDED). 종료된 조는 다시 열지 않고 기록 보완만 저장한다(D-37). */
        public boolean ended() {
            return "ENDED".equals(status);
        }
    }

    public record MemberRow(UUID participantId, UUID userId, String name, int seatNo, String attendance, Instant attendanceRequestedAt,
            Instant attendanceRequestReceivedAt, UUID attendanceCheckedBy, String attendanceCheckedByName, Instant attendanceCheckedAt,
            String proxyAttendanceReason, Instant startedAt, Instant startReceivedAt, String startSource, Instant returnedAt,
            String returnSource, Instant returnConfirmedAt, UUID returnConfirmedBy, String returnConfirmedByName, byte[] phoneCiphertext,
            String returnReason, String abortReason, String requestedReturnReason, String requestedAbortReason, Instant returnRequestedAt,
            boolean paused, Instant pausedChangedAt) {

        public boolean absent() {
            return "ABSENT".equals(attendance);
        }

        public boolean returned() {
            return returnConfirmedAt != null;
        }

        /** 시작·복귀 기록이 있으면 실제 활동한 조원이다(D-30: 결석 처리 불가). */
        public boolean hasActivity() {
            return startedAt != null || returnedAt != null || returnConfirmedAt != null;
        }

        /** 현장 조원: 배정 중이고 결석 확정이 아니며 복귀 확인 전(설계안 3.1). */
        public boolean inField() {
            return !absent() && !returned();
        }
    }

    public record SlotRow(UUID id, int order, String label, String instruction, boolean required, String purpose, String resolution,
            UUID activeInspectionId, UUID acceptedInspectionId, int qualityFailureCount, UUID referenceMediaId, String receiptStatus,
            String captureStatus) {
    }

    /** skipPending: 사진 처리 중이라 적용을 미룬 건너뛰기가 있음(설계안 5.3). */
    public record StopRow(UUID id, int order, UUID facilityId, String facilityType, String name, String visitStatus,
            String notVisitedReason, BigDecimal latitude, BigDecimal longitude, String instruction, List<SlotRow> slots, boolean skipPending) {
    }

    public record MediaRow(UUID id, long version, String privacyStatus) {
    }

    private static final String PATROL_SELECT = """
            SELECT p.id, p.program_id, p.organization_id, o.name AS org_name, p.base_organization_id, p.base_name_snapshot,
                p.activity_date, p.scheduled_start_at, p.expected_return_at, p.team_name_snapshot, p.route_name_snapshot,
                p.activity_status, p.departed_at,
                p.departure_confirmed_at, p.departure_confirmed_by, dc.name AS dc_name, p.paused, p.cancelled_at, p.cancelled_by,
                cb.name AS cb_name, p.cancellation_reason, p.finished_at, p.sync_deadline_at, p.revision, p.version
            FROM patrols p
            JOIN organizations o ON o.id = p.organization_id
            LEFT JOIN users dc ON dc.id = p.departure_confirmed_by
            LEFT JOIN users cb ON cb.id = p.cancelled_by
            WHERE p.id = ?""";

    private final JdbcTemplate jdbc;

    public PatrolReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PatrolRow> patrol(UUID patrolId) {
        return jdbc.query(PATROL_SELECT, this::patrolRow, patrolId).stream().findFirst();
    }

    /** 순회 행을 잠그고 읽는다(DB 설계 12절 잠금 단계 6). */
    public Optional<PatrolRow> lockPatrol(UUID patrolId) {
        jdbc.queryForList("SELECT id FROM patrols WHERE id = ? FOR UPDATE", patrolId);
        return patrol(patrolId);
    }

    public List<MemberRow> members(UUID patrolId) {
        return memberQuery("m.patrol_id = ? AND m.assignment_active", patrolId);
    }

    /** 배정 중이 아닌 행(이동해 나간 조원 등)까지 포함한 한 사람의 배정 행. */
    public Optional<MemberRow> memberRow(UUID patrolId, UUID participantId) {
        return memberQuery("m.patrol_id = ? AND m.participant_id = ?", patrolId, participantId).stream().findFirst();
    }

    /** 이 참여자가 같은 활동일에 지금 배정 중인 순회(이동했으면 새 조). 없으면 빈 값. */
    public Optional<UUID> currentPatrolOf(UUID participantId, java.time.LocalDate activityDate) {
        return jdbc.query("""
                SELECT m.patrol_id FROM patrol_members m JOIN patrols p ON p.id = m.patrol_id
                WHERE m.participant_id = ? AND m.activity_date = ? AND m.assignment_active AND p.activity_status <> 'CANCELLED'
                ORDER BY p.scheduled_start_at DESC, p.id LIMIT 1""", (rs, i) -> rs.getObject(1, UUID.class), participantId, activityDate)
                .stream().findFirst();
    }

    private List<MemberRow> memberQuery(String where, Object... args) {
        return jdbc.query("""
                SELECT m.participant_id, m.user_id_snapshot, m.name_snapshot, m.seat_no, m.attendance, m.attendance_requested_at,
                    m.attendance_request_received_at, m.attendance_checked_by, ac.name AS ac_name, m.attendance_checked_at,
                    m.proxy_attendance_reason, m.started_at, m.start_received_at, m.start_source, m.returned_at, m.return_source,
                    m.return_confirmed_at, m.return_confirmed_by, rc.name AS rc_name, u.phone_ciphertext, m.return_reason, m.abort_reason,
                    m.requested_return_reason, m.requested_abort_reason, m.return_requested_at, m.paused, m.paused_changed_at
                FROM patrol_members m
                JOIN users u ON u.id = m.user_id_snapshot
                LEFT JOIN users ac ON ac.id = m.attendance_checked_by
                LEFT JOIN users rc ON rc.id = m.return_confirmed_by
                """ + " WHERE " + where + " ORDER BY m.seat_no",
                (rs, i) -> new MemberRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4),
                        rs.getString(5), ts(rs, 6), ts(rs, 7), rs.getObject(8, UUID.class), rs.getString(9), ts(rs, 10), rs.getString(11),
                        ts(rs, 12), ts(rs, 13), rs.getString(14), ts(rs, 15), rs.getString(16), ts(rs, 17), rs.getObject(18, UUID.class),
                        rs.getString(19), rs.getBytes(20), rs.getString(21), rs.getString(22), rs.getString(23), rs.getString(24),
                        ts(rs, 25), rs.getBoolean(26), ts(rs, 27)),
                args);
    }

    /** 배정 행을 좌석(=참여자 정렬과 같은 결정적 순서)으로 잠근다(잠금 단계 8). */
    public void lockMembers(UUID patrolId) {
        jdbc.queryForList("SELECT id FROM patrol_members WHERE patrol_id = ? AND assignment_active ORDER BY participant_id FOR UPDATE",
                patrolId);
    }

    public List<StopRow> stops(UUID patrolId) {
        Map<UUID, List<SlotRow>> slots = new LinkedHashMap<>();
        jdbc.query("""
                SELECT s.patrol_stop_id, s.id, s.sequence_no, s.label, s.instruction, s.required, s.purpose, s.resolution,
                    s.active_inspection_id, s.accepted_inspection_id, s.quality_failure_count, s.reference_media_id,
                    i.receipt_status, i.capture_status
                FROM capture_slots s
                JOIN patrol_stops ps ON ps.id = s.patrol_stop_id
                LEFT JOIN LATERAL (
                    SELECT receipt_status, capture_status FROM inspections x
                    WHERE x.capture_slot_id = s.id
                    ORDER BY (x.id = s.accepted_inspection_id) DESC, (x.id = s.active_inspection_id) DESC, x.attempt_no DESC
                    LIMIT 1) i ON true
                WHERE ps.patrol_id = ?
                ORDER BY ps.sequence_no, s.sequence_no""",
                (ResultSet rs) -> {
                    slots.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(new SlotRow(
                            rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getString(7),
                            rs.getString(8), rs.getObject(9, UUID.class), rs.getObject(10, UUID.class), rs.getInt(11),
                            rs.getObject(12, UUID.class), rs.getString(13) == null ? "NONE" : rs.getString(13), rs.getString(14)));
                },
                patrolId);
        return jdbc.query("""
                SELECT id, sequence_no, facility_id, type_snapshot, name_snapshot, visit_status, not_visited_reason, latitude_snapshot,
                    longitude_snapshot, instruction_snapshot, skip_pending_event_id IS NOT NULL
                FROM patrol_stops WHERE patrol_id = ? ORDER BY sequence_no""",
                (rs, i) -> new StopRow(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getString(10),
                        slots.getOrDefault(rs.getObject(1, UUID.class), List.of()), rs.getBoolean(11)),
                patrolId);
    }

    /** 구도에 연결된 참고 사진. 승인된 것만 assets, 그 밖은 회수 목록(revokedMediaIds)이다. */
    public List<MediaRow> referenceMedia(UUID patrolId) {
        return jdbc.query("""
                SELECT DISTINCT md.id, md.version, md.privacy_status FROM capture_slots s
                JOIN patrol_stops ps ON ps.id = s.patrol_stop_id
                JOIN media md ON md.id = s.reference_media_id
                WHERE ps.patrol_id = ? ORDER BY md.id""",
                (rs, i) -> new MediaRow(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3)), patrolId);
    }

    public record ContactRow(String name, byte[] phoneCiphertext) {
    }

    /** 순회 기관·거점을 담당하는 활동 담당자 중 연락처가 있는 사람을 먼저, 그다음 권한 부여 순. */
    public Optional<ContactRow> activityManagerContact(UUID programId, UUID organizationId, UUID baseOrganizationId) {
        return jdbc.query("""
                SELECT u.name, u.phone_ciphertext FROM user_role_scopes s JOIN users u ON u.id = s.user_id
                WHERE s.program_id = ? AND s.role = 'ACTIVITY_MANAGER' AND s.revoked_at IS NULL AND u.status = 'ACTIVE'
                  AND s.organization_id IN (?, ?)
                ORDER BY (u.phone_ciphertext IS NOT NULL) DESC, s.granted_at, u.id LIMIT 1""",
                (rs, i) -> new ContactRow(rs.getString(1), rs.getBytes(2)), programId, organizationId, baseOrganizationId)
                .stream().findFirst();
    }

    private PatrolRow patrolRow(ResultSet rs, int i) throws SQLException {
        return new PatrolRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getObject(7, LocalDate.class), ts(rs, 8), ts(rs, 9), rs.getString(10),
                rs.getString(11), rs.getString(12), ts(rs, 13), ts(rs, 14), rs.getObject(15, UUID.class), rs.getString(16),
                rs.getBoolean(17), ts(rs, 18), rs.getObject(19, UUID.class), rs.getString(20), rs.getString(21), ts(rs, 22), ts(rs, 23),
                rs.getLong(24), rs.getLong(25));
    }

    /** 이 조에서 다른 조로 이동해 나가 지금은 이 조에 배정 중이 아닌 조원 수(D-36). */
    public int transferredOut(UUID patrolId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(DISTINCT pp.participant_id) FROM patrol_participations pp
                JOIN patrol_members m ON m.patrol_id = pp.patrol_id AND m.participant_id = pp.participant_id
                WHERE pp.patrol_id = ? AND pp.leave_reason = 'TRANSFERRED' AND NOT m.assignment_active""", Integer.class, patrolId);
        return n == null ? 0 : n;
    }

    /** 이 조에서 이동해 나가 지금 배정 중이 아닌 조원의 마지막 이동 대상 조. 배정 중이거나 이동한 적이 없으면 빈 값. */
    public Optional<UUID> transferredTo(UUID patrolId, UUID participantId) {
        return jdbc.query("""
                SELECT t.to_patrol_id FROM patrol_participations pp
                JOIN patrol_member_transfers t ON t.id = pp.transfer_out_id
                JOIN patrol_members m ON m.patrol_id = pp.patrol_id AND m.participant_id = pp.participant_id
                WHERE pp.patrol_id = ? AND pp.participant_id = ? AND pp.leave_reason = 'TRANSFERRED' AND NOT m.assignment_active
                ORDER BY pp.left_at DESC, t.id LIMIT 1""", (rs, i) -> rs.getObject(1, UUID.class), patrolId, participantId)
                .stream().findFirst();
    }

    /** 배정 중 조원의 현재 참여 구간: 이동해 들어왔으면 합류 시각과 이전 조. 최초 배정이면 joinedAt=null. */
    public record MembershipRow(UUID participantId, Instant joinedAt, UUID transferredFromPatrolId) {
    }

    public Map<UUID, MembershipRow> openMemberships(UUID patrolId) {
        Map<UUID, MembershipRow> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT pp.participant_id, pp.joined_at, t.from_patrol_id FROM patrol_participations pp
                LEFT JOIN patrol_member_transfers t ON t.id = pp.transfer_in_id
                WHERE pp.patrol_id = ? AND pp.leave_reason IS NULL""",
                (ResultSet rs) -> {
                    out.put(rs.getObject(1, UUID.class), new MembershipRow(rs.getObject(1, UUID.class), ts(rs, 2), rs.getObject(3, UUID.class)));
                }, patrolId);
        return out;
    }

    /** 이 조에서 이동해 나가 지금 배정 중이 아닌 조원(기록 보존, D-36). 마지막 이동 기준. */
    public record FormerMemberRow(UUID participantId, UUID userId, String name, String attendance, Instant startedAt, String startSource,
            Instant leftAt, UUID transferredToPatrolId, UUID transferId) {
    }

    public List<FormerMemberRow> formerMembers(UUID patrolId) {
        return jdbc.query("""
                SELECT DISTINCT ON (m.participant_id) m.participant_id, m.user_id_snapshot, m.name_snapshot, m.attendance, m.started_at,
                    m.start_source, pp.left_at, t.to_patrol_id, t.id
                FROM patrol_members m
                JOIN patrol_participations pp ON pp.patrol_id = m.patrol_id AND pp.participant_id = m.participant_id
                    AND pp.leave_reason = 'TRANSFERRED'
                JOIN patrol_member_transfers t ON t.id = pp.transfer_out_id
                WHERE m.patrol_id = ? AND NOT m.assignment_active
                ORDER BY m.participant_id, pp.left_at DESC""",
                (rs, i) -> new FormerMemberRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        ts(rs, 5), rs.getString(6), ts(rs, 7), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class)),
                patrolId);
    }

    /** 종료 당시 기록(patrol_end_records). 한 번 쓰면 바뀌지 않는다(설계안 3.3). */
    public record EndRecordRow(Instant endedAt, String endReason, String triggerType, UUID triggerParticipantId, int normalReturned,
            int abortReturned, int transferredOut, int absent, String recordSource) {
    }

    public Optional<EndRecordRow> endRecord(UUID patrolId) {
        return jdbc.query("""
                SELECT ended_at, end_reason, trigger_type, trigger_participant_id, normal_returned, abort_returned, transferred_out, absent,
                    record_source
                FROM patrol_end_records WHERE patrol_id = ?""",
                (rs, i) -> new EndRecordRow(ts(rs, 1), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9)),
                patrolId).stream().findFirst();
    }

    static Instant ts(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
