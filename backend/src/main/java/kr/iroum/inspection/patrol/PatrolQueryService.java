package kr.iroum.inspection.patrol;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.patrol.PatrolReader.MemberRow;
import kr.iroum.inspection.patrol.PatrolReader.PatrolRow;
import kr.iroum.inspection.patrol.PatrolReader.StopRow;
import kr.iroum.inspection.patrol.PatrolViews.*;

/**
 * 순회 조회(업무 API 6.2·8절). GET은 상태를 바꾸지 않는다. 상세·sync-state·오프라인 패키지는 같은 REPEATABLE READ 스냅샷에서 읽는다.
 * 시니어 응답에는 다른 조원 전화번호·시설 이상·판독 근거를 넣지 않는다.
 */
@Service
public class PatrolQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int RECENT_EVENTS = 20;

    private final PatrolReader reader;
    private final AccessPolicy access;
    private final PhoneCrypto phones;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PatrolQueryService(PatrolReader reader, AccessPolicy access, PhoneCrypto phones, JdbcTemplate jdbc, Clock clock) {
        this.reader = reader;
        this.access = access;
        this.phones = phones;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 상세 조회(AccessPolicy.requireAssignedPatrol)와 같은 활성 조건: 유효 배정, 참여자 ACTIVE, 그 사업의 SENIOR 역할. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<TodayPatrol> today(AuthenticatedUser user, LocalDate date, Integer page, Integer size) {
        access.requireAnyRole(user, AccessPolicy.SENIOR);
        int p = page(page);
        int s = size(size);
        List<UUID> ids = jdbc.queryForList("""
                SELECT p.id FROM patrols p
                JOIN patrol_members m ON m.patrol_id = p.id AND m.assignment_active
                JOIN participants pa ON pa.id = m.participant_id AND pa.program_id = m.program_id AND pa.user_id = ? AND pa.status = 'ACTIVE'
                WHERE p.activity_date = ?
                  AND EXISTS (SELECT 1 FROM user_role_scopes s WHERE s.user_id = pa.user_id AND s.program_id = m.program_id
                      AND s.role = 'SENIOR' AND s.revoked_at IS NULL)
                ORDER BY p.scheduled_start_at, p.id LIMIT ? OFFSET ?""", UUID.class, user.userId(), date, s, (long) p * s);
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM patrols p
                JOIN patrol_members m ON m.patrol_id = p.id AND m.assignment_active
                JOIN participants pa ON pa.id = m.participant_id AND pa.program_id = m.program_id AND pa.user_id = ? AND pa.status = 'ACTIVE'
                WHERE p.activity_date = ?
                  AND EXISTS (SELECT 1 FROM user_role_scopes s WHERE s.user_id = pa.user_id AND s.program_id = m.program_id
                      AND s.role = 'SENIOR' AND s.revoked_at IS NULL)""", Long.class, user.userId(), date);
        List<TodayPatrol> items = new ArrayList<>();
        for (UUID id : ids) {
            PatrolRow patrol = reader.patrol(id).orElseThrow();
            List<MemberRow> members = reader.members(id);
            MemberRow me = members.stream().filter(m -> m.userId().equals(user.userId())).findFirst().orElse(null);
            items.add(new TodayPatrol(patrol.id(), patrol.activityDate(), patrol.status(), patrol.teamName(), patrol.routeName(),
                    meetingPoint(patrol), patrol.scheduledStartAt(), patrol.expectedReturnAt(), members.size(),
                    me == null ? "UNCHECKED" : me.attendance(), me == null ? null : me.attendanceRequestedAt(),
                    me == null ? null : me.startedAt(), progress(reader.stops(id))));
        }
        return new Page<>(items, p, s, total == null ? 0 : total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Patrol patrol(AuthenticatedUser user, UUID patrolId) {
        AccessPolicy.SeniorAssignment me = access.requireSeniorParticipant(user, patrolId);
        return seniorPatrol(load(patrolId), me);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SyncState syncState(AuthenticatedUser user, UUID patrolId) {
        AccessPolicy.SeniorAssignment me = access.requireSeniorParticipant(user, patrolId);
        Snapshot snap = load(patrolId);
        PatrolRow p = snap.patrol();
        List<SyncMember> members = snap.members().stream().map(m -> new SyncMember(m.participantId(), m.attendance(), m.startedAt(),
                m.startSource(), m.returnedAt(), m.returnSource(), m.returned(), m.returnConfirmedAt(), m.returnReason(), m.abortReason(),
                returnRequest(m), m.paused())).toList();
        List<SyncStop> stops = snap.stops().stream().map(st -> new SyncStop(st.id(), st.visitStatus(), st.slots().stream()
                .map(sl -> new SyncSlot(sl.id(), sl.receiptStatus(), sl.captureStatus(), sl.activeInspectionId(),
                        sl.acceptedInspectionId(), retakesRemaining(sl.qualityFailureCount()), sl.referenceMediaId(),
                        sl.qualityFailureCount()))
                .toList(), st.skipPending())).toList();
        return new SyncState(p.id(), p.revision(), p.version(), p.status(), end(p.id()), p.departedAt(),
                p.departureConfirmedAt(), soloWaiting(p.id()), syncAllowed(p), p.syncDeadlineAt(), currentStop(snap.stops()),
                progress(snap.stops()), members, stops, assets(snap), revoked(snap), myAssignment(patrolId, me));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OfflinePackage offlinePackage(AuthenticatedUser user, UUID patrolId) {
        AccessPolicy.SeniorAssignment me = access.requireSeniorParticipant(user, patrolId);
        Snapshot snap = load(patrolId);
        // 오프라인 자료의 버전은 순회 revision이다. 다음 연결 때 sync-state의 회수 목록으로 폐기한다.
        return new OfflinePackage(snap.patrol().revision(), now(), seniorPatrol(snap, me), assets(snap));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<AdminPatrolItem> adminList(AuthenticatedUser user, LocalDate date, String activityStatus, UUID organizationId,
            Integer page, Integer size) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        int p = page(page);
        int s = size(size);
        String where = """
                FROM patrols p WHERE EXISTS (SELECT 1 FROM scoped sc WHERE sc.program_id = p.program_id
                    AND sc.id IN (p.organization_id, p.base_organization_id))
                  AND (?::date IS NULL OR p.activity_date = ?::date)
                  AND (?::varchar IS NULL OR p.activity_status = ?::varchar)
                  AND (?::uuid IS NULL OR p.organization_id = ?::uuid)""";
        Object[] args = {user.userId(), AccessPolicy.ACTIVITY_MANAGER, date, date, activityStatus, activityStatus, organizationId,
                organizationId};
        List<UUID> ids = jdbc.queryForList(AccessPolicy.scopedOrganizationsCte() + "SELECT p.id " + where
                + " ORDER BY p.activity_date DESC, p.scheduled_start_at, p.id LIMIT " + s + " OFFSET " + ((long) p * s), UUID.class, args);
        Long total = jdbc.queryForObject(AccessPolicy.scopedOrganizationsCte() + "SELECT count(*) " + where, Long.class, args);
        List<AdminPatrolItem> items = new ArrayList<>();
        for (UUID id : ids) {
            Snapshot snap = load(id);
            PatrolRow r = snap.patrol();
            List<MemberRow> ms = snap.members();
            AttendanceSummary summary = new AttendanceSummary(ms.size(), count(ms, "PRESENT"), count(ms, "ABSENT"), count(ms, "UNCHECKED"),
                    (int) ms.stream().filter(m -> m.attendanceRequestedAt() != null).count());
            items.add(new AdminPatrolItem(r.id(), r.version(), r.activityDate(), r.organizationId(), r.organizationName(), r.teamName(),
                    r.routeName(), r.status(), r.scheduledStartAt(), r.expectedReturnAt(), r.departedAt(), r.departureConfirmedAt(),
                    summary, (int) ms.stream().filter(m -> startMissing(r, m)).count(),
                    (int) ms.stream().filter(MemberRow::returned).count(), returnOverdue(r), progress(snap.stops()), lastActivityAt(id),
                    pendingReturnConfirmations(r, ms), soloWaiting(id)));
        }
        return new Page<>(items, p, s, total == null ? 0 : total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminPatrol adminPatrol(AuthenticatedUser user, UUID patrolId) {
        access.requireManagedPatrol(user, patrolId);
        Snapshot snap = load(patrolId);
        PatrolRow p = snap.patrol();
        Map<UUID, PatrolReader.MembershipRow> memberships = reader.openMemberships(patrolId);
        List<AdminMember> members = snap.members().stream().map(m -> new AdminMember(m.participantId(), m.userId(), m.name(),
                m.seatNo(), m.attendance(), m.attendanceRequestedAt(), m.startedAt(), m.startReceivedAt(), m.startSource(),
                m.returnedAt(), m.returnSource(), phone(m.phoneCiphertext()), m.attendanceCheckedAt(),
                actor(m.attendanceCheckedBy(), m.attendanceCheckedByName()), m.proxyAttendanceReason(),
                actor(m.returnConfirmedBy(), m.returnConfirmedByName()), startMissing(p, m), m.returned(), m.returnConfirmedAt(),
                m.returnReason(), m.abortReason(), returnRequest(m), joinedAt(memberships, m), transferredFrom(memberships, m), m.paused()))
                .toList();
        List<FormerMember> former = reader.formerMembers(patrolId).stream().map(r -> new FormerMember(r.participantId(), r.userId(), r.name(),
                r.attendance(), r.startedAt(), r.startSource(), r.leftAt(), r.transferredToPatrolId(), r.transferId())).toList();
        List<RecentEvent> events = jdbc.query("""
                SELECT e.id, e.type, u.id, u.name, e.patrol_stop_id, e.reason_code, e.occurred_at, e.received_at
                FROM patrol_events e JOIN users u ON u.id = e.actor_user_id
                WHERE e.patrol_id = ? ORDER BY e.received_at DESC, e.id LIMIT ?""",
                (rs, i) -> new RecentEvent(rs.getObject(1, UUID.class), rs.getString(2), new Actor(rs.getObject(3, UUID.class),
                        rs.getString(4)), rs.getObject(5, UUID.class), rs.getString(6), PatrolReader.ts(rs, 7), PatrolReader.ts(rs, 8)),
                patrolId, RECENT_EVENTS);
        return new AdminPatrol(p.id(), p.version(), p.revision(), p.activityDate(), p.status(), end(p.id()), currentOutcomes(snap),
                supplement(p), pendingReturnConfirmations(p, snap.members()), p.teamName(), p.routeName(), meetingPoint(p), p.scheduledStartAt(), p.expectedReturnAt(), p.departedAt(),
                p.departureConfirmedAt(), soloWaiting(p.id()), members, former, progress(snap.stops()), currentStop(snap.stops()), stops(snap.stops()),
                syncAllowed(p), p.syncDeadlineAt(), p.organizationId(), p.organizationName(),
                actor(p.departureConfirmedBy(), p.departureConfirmedByName()), returnOverdue(p), p.cancelledAt(),
                actor(p.cancelledBy(), p.cancelledByName()), p.cancellationReason(), events);
    }

    record Snapshot(PatrolRow patrol, List<MemberRow> members, List<StopRow> stops, List<PatrolReader.MediaRow> media) {
    }

    private Snapshot load(UUID patrolId) {
        PatrolRow patrol = reader.patrol(patrolId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                "찾을 수 없거나 접근할 수 없는 항목입니다."));
        return new Snapshot(patrol, reader.members(patrolId), reader.stops(patrolId), reader.referenceMedia(patrolId));
    }

    private MyAssignment myAssignment(UUID patrolId, AccessPolicy.SeniorAssignment me) {
        if (me.active()) {
            return new MyAssignment(me.participantId(), me.memberId(), true, null, null);
        }
        PatrolReader.FormerMemberRow left = reader.formerMembers(patrolId).stream().filter(r -> r.participantId().equals(me.participantId()))
                .findFirst().orElseThrow();
        return new MyAssignment(me.participantId(), me.memberId(), false, left.leftAt(), left.transferredToPatrolId());
    }

    private static Instant joinedAt(Map<UUID, PatrolReader.MembershipRow> memberships, MemberRow m) {
        PatrolReader.MembershipRow r = memberships.get(m.participantId());
        return r == null ? null : r.joinedAt();
    }

    private static UUID transferredFrom(Map<UUID, PatrolReader.MembershipRow> memberships, MemberRow m) {
        PatrolReader.MembershipRow r = memberships.get(m.participantId());
        return r == null ? null : r.transferredFromPatrolId();
    }

    private Patrol seniorPatrol(Snapshot snap, AccessPolicy.SeniorAssignment me) {
        PatrolRow p = snap.patrol();
        List<Member> members = snap.members().stream().map(m -> new Member(m.participantId(), m.userId(), m.name(), m.seatNo(),
                m.attendance(), m.attendanceRequestedAt(), m.startedAt(), m.startReceivedAt(), m.startSource(), m.returnedAt(),
                m.returnSource(), m.returned(), m.returnConfirmedAt(), m.returnReason(), m.abortReason(), returnRequest(m), m.paused()))
                .toList();
        Contact contact = reader.activityManagerContact(p.programId(), p.organizationId(), p.baseOrganizationId())
                .map(c -> new Contact(c.name(), phone(c.phoneCiphertext())))
                .orElse(new Contact(p.organizationName(), ""));
        return new Patrol(p.id(), p.version(), p.revision(), p.activityDate(), p.status(), end(p.id()), p.teamName(), p.routeName(), meetingPoint(p), p.scheduledStartAt(), p.expectedReturnAt(), p.departedAt(),
                p.departureConfirmedAt(), soloWaiting(p.id()), members, progress(snap.stops()), currentStop(snap.stops()), stops(snap.stops()),
                contact, syncAllowed(p), p.syncDeadlineAt(), myAssignment(p.id(), me));
    }

    private End end(UUID patrolId) {
        return reader.endRecord(patrolId).map(r -> new End(r.endedAt(), r.endReason(), r.triggerType(), new Outcomes(r.normalReturned(),
                r.abortReturned(), r.transferredOut(), r.absent()), r.recordSource())).orElse(null);
    }

    private static ReturnRequest returnRequest(MemberRow m) {
        return m.requestedReturnReason() == null ? null
                : new ReturnRequest(m.requestedReturnReason(), m.requestedAbortReason(), m.returnRequestedAt());
    }

    private CurrentOutcomes currentOutcomes(Snapshot snap) {
        List<MemberRow> ms = snap.members();
        return new CurrentOutcomes((int) ms.stream().filter(m -> m.returned() && "NORMAL".equals(m.returnReason())).count(),
                (int) ms.stream().filter(m -> m.returned() && "ABORT".equals(m.returnReason())).count(), transferredOut(snap.patrol().id()),
                (int) ms.stream().filter(MemberRow::absent).count(), (int) ms.stream().filter(MemberRow::inField).count());
    }

    private int transferredOut(UUID patrolId) {
        return reader.transferredOut(patrolId);
    }

    /** 종료된 조에서 아직 복귀 확인을 받지 않은 현장 조원 수(사후 보완 대상). 진행 중이면 0. */
    private static int pendingReturnConfirmations(PatrolRow p, List<MemberRow> members) {
        return p.ended() ? (int) members.stream().filter(MemberRow::inField).count() : 0;
    }

    /** 종료 뒤 저장된 기록 보완: 시각 정정·복귀 확인·출결 변경. 진행 중이면 null. */
    private Supplement supplement(PatrolRow p) {
        if (!p.ended()) {
            return null;
        }
        return jdbc.queryForObject("""
                SELECT count(*), max(at) FROM (
                    SELECT recorded_at AS at FROM patrol_time_corrections WHERE patrol_id = ? AND recorded_at > ?
                    UNION ALL
                    SELECT return_confirmed_at FROM patrol_members WHERE patrol_id = ? AND assignment_active AND return_confirmed_at > ?
                    UNION ALL
                    SELECT recorded_at FROM patrol_attendance_changes WHERE patrol_id = ? AND recorded_at > ?
                ) s""", (rs, i) -> new Supplement(rs.getInt(1), PatrolReader.ts(rs, 2)), p.id(), java.sql.Timestamp.from(p.finishedAt()),
                p.id(), java.sql.Timestamp.from(p.finishedAt()), p.id(), java.sql.Timestamp.from(p.finishedAt()));
    }

    static List<Stop> stops(List<StopRow> rows) {
        return rows.stream().map(st -> new Stop(st.id(), st.order(), st.facilityId(), st.facilityType(), st.name(), st.visitStatus(),
                st.latitude(), st.longitude(), st.instruction(), st.slots().stream().map(sl -> new Slot(sl.id(), sl.order(), sl.label(),
                        sl.instruction(), sl.required(), sl.purpose(), sl.receiptStatus(), sl.captureStatus(), sl.activeInspectionId(),
                        sl.acceptedInspectionId(), retakesRemaining(sl.qualityFailureCount()), sl.referenceMediaId(),
                        sl.qualityFailureCount())).toList(), st.skipPending())).toList();
    }

    /** 지금 열린 혼자 남은 대기(D-35). */
    private SoloWaiting soloWaiting(UUID patrolId) {
        return jdbc.query("SELECT participant_id, started_at FROM patrol_solo_waits WHERE patrol_id = ? AND ended_at IS NULL",
                (rs, i) -> new SoloWaiting(rs.getObject(1, UUID.class), PatrolReader.ts(rs, 2)), patrolId).stream().findFirst().orElse(null);
    }

    static Progress progress(List<StopRow> stops) {
        int pending = 0, captured = 0, skipped = 0, review = 0, notVisited = 0;
        for (StopRow s : stops) {
            switch (s.visitStatus()) {
                case "PENDING" -> pending++;
                case "CAPTURED" -> captured++;
                case "SKIPPED" -> skipped++;
                case "REVIEW_REQUESTED" -> review++;
                default -> notVisited++;
            }
        }
        return new Progress(stops.size(), pending, captured, skipped, review, notVisited);
    }

    /** 다음에 안내할 장소: 순서상 첫 PENDING 장소. */
    static UUID currentStop(List<StopRow> stops) {
        return stops.stream().filter(s -> "PENDING".equals(s.visitStatus())).map(StopRow::id).findFirst().orElse(null);
    }

    static int retakesRemaining(int qualityFailureCount) {
        return Math.max(0, Math.min(2, 3 - qualityFailureCount));
    }

    private List<Asset> assets(Snapshot snap) {
        return snap.media().stream().filter(m -> "APPROVED".equals(m.privacyStatus()))
                .map(m -> new Asset(m.id(), m.version(), "/api/v1/media/" + m.id() + "/content")).toList();
    }

    private List<UUID> revoked(Snapshot snap) {
        return snap.media().stream().filter(m -> !"APPROVED".equals(m.privacyStatus())).map(PatrolReader.MediaRow::id).toList();
    }

    /** 취소되지 않았고 사진 접수 기한(D-17) 전이면 전송을 계속한다. */
    private boolean syncAllowed(PatrolRow p) {
        return !"CANCELLED".equals(p.status()) && now().isBefore(p.syncDeadlineAt());
    }

    /** 예정 복귀 시각이 지났는데 끝나지 않음(화면 표시용, 알림 없음). */
    private boolean returnOverdue(PatrolRow p) {
        return !p.terminal() && now().isAfter(p.expectedReturnAt());
    }

    /** 조가 출발했는데 결석 아닌 조원의 시작 기록이 없음(자동 보정하지 않고 표시만). */
    static boolean startMissing(PatrolRow p, MemberRow m) {
        boolean departed = p.departedAt() != null || !("ASSIGNED".equals(p.status()) || "CANCELLED".equals(p.status()));
        return departed && !m.absent() && m.startedAt() == null;
    }

    private Instant lastActivityAt(UUID patrolId) {
        // 이벤트가 없으면 max()는 NULL 한 행이다.
        return jdbc.query("SELECT max(received_at) FROM patrol_events WHERE patrol_id = ?", (rs, i) -> PatrolReader.ts(rs, 1), patrolId)
                .get(0);
    }

    private String phone(byte[] ciphertext) {
        return ciphertext == null || !phones.configured() ? "" : phones.decrypt(ciphertext);
    }

    private static MeetingPoint meetingPoint(PatrolRow p) {
        return new MeetingPoint(p.baseOrganizationId(), p.baseName());
    }

    private static Actor actor(UUID id, String name) {
        return id == null ? null : new Actor(id, name);
    }

    private static int count(List<MemberRow> members, String attendance) {
        return (int) members.stream().filter(m -> attendance.equals(m.attendance())).count();
    }

    private static int page(Integer page) {
        if (page != null && page < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 형식을 확인해 주세요.");
        }
        return page == null ? 0 : page;
    }

    private static int size(Integer size) {
        if (size != null && (size < 1 || size > MAX_PAGE_SIZE)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 형식을 확인해 주세요.");
        }
        return size == null ? 20 : size;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
