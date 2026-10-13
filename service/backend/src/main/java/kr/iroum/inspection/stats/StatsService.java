package kr.iroum.inspection.stats;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.config.TimeConfig;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;

/**
 * 기간 집계(기능정의서 OPS-03, 사업 총괄). DB에 기록된 값만 센다.
 * <ul>
 * <li>사진 수·점검 장소 수·사건 수·출결을 따로 센다. 사진 수로 활동 시간을 계산하지 않고, 경과 시간·인정 실적도 내지 않는다(D-07·D-10).</li>
 * <li>순회는 끝난(ENDED) 순회만, 참여 연인원은 담당자가 출석을 확인한(PRESENT) 조원 수다.</li>
 * <li>날짜는 한국 시간 기준. 기간 지정이 없으면 오늘까지 최근 14일. 부서별·시설별 사건은 기간과 상관없이 전체다.</li>
 * </ul>
 * service/: 원본에는 없던 기능이다(B-2).
 */
@Service
public class StatsService {

    public record Summary(int patrols, int aborted, int participantDays, int photos, int facilitiesChecked, int skipped, int notVisited, int reviews,
            int reviewsResolved, int reviewsConfirmed, int issuesNew, int repairsDone, int issuesClosed, int repairAgain) {
    }

    public record DeptRow(String department, int open, int closed, int repairAgain) {
    }

    public record TypeRow(String facilityType, int issues) {
    }

    public record ActivityRow(LocalDate date, String team, String route, List<String> members, boolean aborted, int facilitiesChecked, int skipped,
            int notVisited) {
    }

    public record IssueRow(UUID id, String facilityType, String facilityName, String summary, String status, String department, java.time.Instant openedAt,
            int repairs, java.time.Instant closedAt) {
    }

    public record Report(LocalDate from, LocalDate to, Summary summary, List<DeptRow> byDepartment, List<TypeRow> byFacilityType,
            List<ActivityRow> activities, List<IssueRow> issues) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final Clock clock;

    public StatsService(JdbcTemplate jdbc, AccessPolicy access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report report(AuthenticatedUser user, LocalDate from, LocalDate to) {
        access.requireAnyRole(user, AccessPolicy.PROGRAM_MANAGER);
        UUID program = jdbc.queryForList("""
                SELECT program_id FROM user_role_scopes WHERE user_id = ? AND role = 'PROGRAM_MANAGER' AND revoked_at IS NULL ORDER BY granted_at LIMIT 1""",
                UUID.class, user.userId()).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다."));
        LocalDate end = to == null ? LocalDate.now(clock.withZone(TimeConfig.ACTIVITY_ZONE)) : to;
        LocalDate start = from == null ? end.minusDays(13) : from;
        if (end.isBefore(start) || start.isBefore(end.minusDays(366))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "기간을 확인해 주세요(최대 1년).");
        }
        Date s = Date.valueOf(start);
        Date e = Date.valueOf(end);
        String kst = " AT TIME ZONE 'Asia/Seoul')::date BETWEEN ? AND ?";

        Summary summary = jdbc.queryForObject("""
                WITH ended AS (SELECT id, return_reason FROM patrols WHERE program_id = ? AND activity_status = 'ENDED' AND activity_date BETWEEN ? AND ?)
                SELECT
                  (SELECT count(*) FROM ended),
                  (SELECT count(*) FROM ended WHERE return_reason = 'ABORT'),
                  (SELECT count(*) FROM patrol_members m WHERE m.patrol_id IN (SELECT id FROM ended) AND m.attendance = 'PRESENT'),
                  (SELECT count(*) FROM inspections i WHERE i.program_id = ? AND i.received_at IS NOT NULL AND (i.captured_at""" + kst + """
                  ),
                  (SELECT count(*) FROM patrol_stops ps WHERE ps.patrol_id IN (SELECT id FROM ended) AND ps.visit_status IN ('CAPTURED', 'REVIEW_REQUESTED')),
                  (SELECT count(*) FROM patrol_stops ps WHERE ps.patrol_id IN (SELECT id FROM ended) AND ps.visit_status = 'SKIPPED'),
                  (SELECT count(*) FROM patrol_stops ps WHERE ps.patrol_id IN (SELECT id FROM ended) AND ps.visit_status = 'NOT_VISITED'),
                  (SELECT count(*) FROM review_tasks t WHERE t.program_id = ? AND (t.created_at""" + kst + """
                  ),
                  (SELECT count(*) FROM review_tasks t WHERE t.program_id = ? AND t.status = 'RESOLVED' AND (t.created_at""" + kst + """
                  ),
                  (SELECT count(DISTINCT d.review_task_id) FROM review_decisions d WHERE d.program_id = ? AND d.decision = 'ISSUE_CONFIRMED' AND (d.decided_at""" + kst + """
                  ),
                  (SELECT count(*) FROM issues x WHERE x.program_id = ? AND (x.opened_at""" + kst + """
                  ),
                  (SELECT count(*) FROM repair_events r WHERE r.program_id = ? AND r.type = 'COMPLETED' AND (r.occurred_at""" + kst + """
                  ),
                  (SELECT count(*) FROM issues x WHERE x.program_id = ? AND x.status = 'CLOSED' AND (x.closed_at""" + kst + """
                  ),
                  (SELECT count(*) FROM repair_events r WHERE r.program_id = ? AND r.type = 'RE_REQUESTED' AND (r.occurred_at""" + kst + """
                  )""",
                (rs, i) -> new Summary(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                        rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getInt(13), rs.getInt(14)),
                program, s, e, program, s, e, program, s, e, program, s, e, program, s, e, program, s, e, program, s, e, program, s, e, program, s, e);

        List<DeptRow> byDept = jdbc.query("""
                SELECT o.name,
                       count(*) FILTER (WHERE x.status <> 'CLOSED'),
                       count(*) FILTER (WHERE x.status = 'CLOSED'),
                       coalesce(sum((SELECT count(*) FROM repair_events r WHERE r.issue_id = x.id AND r.type = 'RE_REQUESTED')), 0)
                FROM issues x JOIN organizations o ON o.id = x.assigned_department_id
                WHERE x.program_id = ? GROUP BY o.name ORDER BY count(*) DESC, o.name""",
                (rs, i) -> new DeptRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)), program);

        List<TypeRow> byType = jdbc.query("""
                SELECT f.type, count(*) FROM issues x JOIN facilities f ON f.id = x.facility_id WHERE x.program_id = ?
                GROUP BY f.type ORDER BY count(*) DESC, f.type""", (rs, i) -> new TypeRow(rs.getString(1), rs.getInt(2)), program);

        List<ActivityRow> activities = jdbc.query("""
                SELECT p.activity_date, p.team_name_snapshot, p.route_name_snapshot, p.return_reason,
                       (SELECT array_agg(m.name_snapshot ORDER BY m.seat_no) FROM patrol_members m WHERE m.patrol_id = p.id AND m.attendance = 'PRESENT'),
                       (SELECT count(*) FROM patrol_stops ps WHERE ps.patrol_id = p.id AND ps.visit_status IN ('CAPTURED', 'REVIEW_REQUESTED')),
                       (SELECT count(*) FROM patrol_stops ps WHERE ps.patrol_id = p.id AND ps.visit_status = 'SKIPPED'),
                       (SELECT count(*) FROM patrol_stops ps WHERE ps.patrol_id = p.id AND ps.visit_status = 'NOT_VISITED')
                FROM patrols p WHERE p.program_id = ? AND p.activity_status = 'ENDED' AND p.activity_date BETWEEN ? AND ?
                ORDER BY p.activity_date, p.scheduled_start_at""",
                (rs, i) -> {
                    java.sql.Array names = rs.getArray(5);
                    return new ActivityRow(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getString(3), names == null ? List.of() : List.of((String[]) names.getArray()),
                            "ABORT".equals(rs.getString(4)), rs.getInt(6), rs.getInt(7), rs.getInt(8));
                }, program, s, e);

        List<IssueRow> issues = jdbc.query("""
                SELECT x.id, f.type, f.name, x.summary, x.status, o.name, x.opened_at,
                       (SELECT count(*) FROM repair_events r WHERE r.issue_id = x.id AND r.type IN ('REQUESTED', 'RE_REQUESTED')), x.closed_at
                FROM issues x JOIN facilities f ON f.id = x.facility_id JOIN organizations o ON o.id = x.assigned_department_id
                WHERE x.program_id = ? ORDER BY x.opened_at DESC""",
                (rs, i) -> new IssueRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7).toInstant(), rs.getInt(8), rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant()),
                program);

        return new Report(start, end, summary, byDept, byType, activities, issues);
    }
}
