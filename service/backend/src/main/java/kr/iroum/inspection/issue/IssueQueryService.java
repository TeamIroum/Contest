package kr.iroum.inspection.issue;

import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.issue.IssueViews.IssueDetail;
import kr.iroum.inspection.issue.IssueViews.IssueInspection;
import kr.iroum.inspection.issue.IssueViews.IssueItem;
import kr.iroum.inspection.issue.IssueViews.IssueReview;
import kr.iroum.inspection.issue.IssueViews.ReinspectionAssignment;
import kr.iroum.inspection.issue.IssueViews.ReinspectionRequest;
import kr.iroum.inspection.issue.IssueViews.RepairEvent;
import kr.iroum.inspection.review.ReviewQueryService;
import kr.iroum.inspection.review.ReviewReader;
import kr.iroum.inspection.review.ReviewViews.Actor;
import kr.iroum.inspection.review.ReviewViews.Department;
import kr.iroum.inspection.review.ReviewViews.FacilityDetail;
import kr.iroum.inspection.review.ReviewViews.FacilitySummary;
import kr.iroum.inspection.review.ReviewViews.Media;
import kr.iroum.inspection.review.ReviewViews.Page;

/**
 * 시설 문제 조회(업무 API 10절, ISSUE-01). FACILITY_MANAGER만, 시설 대장상 현재 관리 부서가 본인 범위인 시설의 문제만(D-25 현재 관할).
 * 역할 없음 403, 범위 밖·없음 404. 목록은 등록순이며 위험도·등급·우선순위로 정렬하지 않는다. 읽기 전용 REPEATABLE READ로 같은 스냅샷을 읽는다.
 */
@Service
public class IssueQueryService {

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final ReviewReader reader;

    public IssueQueryService(JdbcTemplate jdbc, AccessPolicy access, ReviewReader reader) {
        this.jdbc = jdbc;
        this.access = access;
        this.reader = reader;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<IssueItem> list(AuthenticatedUser user, String status, UUID departmentId, UUID facilityId, Integer page, Integer size) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.max(1, Math.min(100, size));
        String where = """
                FROM issues i JOIN facilities f ON f.id = i.facility_id
                JOIN scoped sc ON sc.program_id = f.program_id AND sc.id = f.managing_department_id
                JOIN organizations d ON d.id = i.assigned_department_id
                WHERE (?::varchar IS NULL OR i.status = ?::varchar) AND (?::uuid IS NULL OR i.assigned_department_id = ?::uuid)
                  AND (?::uuid IS NULL OR i.facility_id = ?::uuid)""";
        Object[] args = {user.userId(), AccessPolicy.FACILITY_MANAGER, status, status, departmentId, departmentId, facilityId, facilityId};
        List<IssueItem> items = jdbc.query(AccessPolicy.scopedOrganizationsCte() + """
                SELECT DISTINCT i.id, i.status, i.version, i.defect_code, i.summary, f.id AS facility_id, f.facility_code, f.type, f.name,
                    f.area_label, d.id AS dept_id, d.name AS dept_name, i.current_reinspection_request_id, i.opened_at, i.closed_at,
                    (SELECT count(*) FROM issue_inspections x WHERE x.issue_id = i.id AND x.unlinked_at IS NULL) AS linked
                """ + where + " ORDER BY i.opened_at, i.id LIMIT " + s + " OFFSET " + ((long) p * s),
                (rs, n) -> new IssueItem(rs.getObject("id", UUID.class), rs.getString("status"), rs.getLong("version"), rs.getString("defect_code"),
                        rs.getString("summary"),
                        new FacilitySummary(rs.getObject("facility_id", UUID.class), rs.getString("facility_code"), rs.getString("type"),
                                rs.getString("name"), area(rs.getString("area_label"))),
                        new Department(rs.getObject("dept_id", UUID.class), rs.getString("dept_name")), rs.getInt("linked"),
                        rs.getObject("current_reinspection_request_id", UUID.class),
                        ReviewReader.ts(rs.getObject("opened_at", OffsetDateTime.class)), ReviewReader.ts(rs.getObject("closed_at", OffsetDateTime.class))),
                args);
        Long total = jdbc.queryForObject(AccessPolicy.scopedOrganizationsCte() + "SELECT count(DISTINCT i.id) " + where, Long.class, args);
        return new Page<>(items, p, s, total == null ? 0 : total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IssueDetail detail(AuthenticatedUser user, UUID issueId) {
        Map<String, Object> i = requireIssue(user, issueId);
        Map<String, Object> f = jdbc.queryForMap("""
                SELECT f.id, f.facility_code, f.type, f.name, f.area_label, o.id AS dept_id, o.name AS dept_name
                FROM facilities f JOIN organizations o ON o.id = f.managing_department_id WHERE f.id = ?""", i.get("facility_id"));
        Department assigned = jdbc.queryForObject("SELECT id, name FROM organizations WHERE id = ?",
                (rs, n) -> new Department(rs.getObject(1, UUID.class), rs.getString(2)), i.get("assigned_department_id"));
        return new IssueDetail(issueId, (String) i.get("status"), ((Number) i.get("version")).longValue(), (String) i.get("defect_code"),
                (String) i.get("summary"),
                new FacilityDetail((UUID) f.get("id"), (String) f.get("facility_code"), (String) f.get("type"), (String) f.get("name"),
                        area((String) f.get("area_label")), new Department((UUID) f.get("dept_id"), (String) f.get("dept_name"))),
                assigned, (UUID) i.get("source_review_decision_id"), (UUID) i.get("previous_issue_id"), (UUID) i.get("current_reinspection_request_id"),
                instant(i.get("opened_at")), instant(i.get("closed_at")), inspections(issueId), reviews(issueId), repairEvents(issueId),
                requests(issueId));
    }

    /** 문제 행과 현재 관할 확인. 역할 없음 403, 없음·범위 밖 404. */
    Map<String, Object> requireIssue(AuthenticatedUser user, UUID issueId) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        Map<String, Object> i = jdbc.queryForList("SELECT * FROM issues WHERE id = ?", issueId).stream().findFirst()
                .orElseThrow(ReviewQueryService::notFound);
        access.requireFacility(user, (UUID) i.get("facility_id"));
        return i;
    }

    private List<IssueInspection> inspections(UUID issueId) {
        List<IssueInspection> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT x.inspection_id, pa.id AS patrol_id, pa.activity_date, i.capture_slot_id, i.captured_at, x.linked_by, lu.name AS linked_name,
                    x.linked_at, x.reason, x.unlinked_by, uu.name AS unlinked_name, x.unlinked_at
                FROM issue_inspections x JOIN inspections i ON i.id = x.inspection_id JOIN capture_slots s ON s.id = i.capture_slot_id
                JOIN patrol_stops ps ON ps.id = s.patrol_stop_id JOIN patrols pa ON pa.id = ps.patrol_id
                JOIN users lu ON lu.id = x.linked_by LEFT JOIN users uu ON uu.id = x.unlinked_by
                WHERE x.issue_id = ? ORDER BY x.linked_at, x.id""", issueId)) {
            UUID inspection = (UUID) r.get("inspection_id");
            Media media = reader.inspection(inspection).map(reader::media).orElse(null);
            out.add(new IssueInspection(inspection, (UUID) r.get("patrol_id"), ((Date) r.get("activity_date")).toLocalDate(),
                    (UUID) r.get("capture_slot_id"), instant(r.get("captured_at")), media,
                    new Actor((UUID) r.get("linked_by"), (String) r.get("linked_name")), instant(r.get("linked_at")), (String) r.get("reason"),
                    r.get("unlinked_by") == null ? null : new Actor((UUID) r.get("unlinked_by"), (String) r.get("unlinked_name")),
                    instant(r.get("unlinked_at"))));
        }
        return out;
    }

    private List<IssueReview> reviews(UUID issueId) {
        return jdbc.query("""
                SELECT d.id, d.review_task_id, x.mode, x.inspection_id, d.decision, d.analysis_result_id, d.classification_correction_id,
                    d.classification_version, d.decided_by, u.name, d.decided_at
                FROM review_decision_issues x JOIN review_decisions d ON d.id = x.review_decision_id JOIN users u ON u.id = d.decided_by
                WHERE x.issue_id = ? ORDER BY d.decided_at, d.id, x.sequence_no""",
                (rs, n) -> new IssueReview(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), (Integer) rs.getObject(8),
                        new Actor(rs.getObject(9, UUID.class), rs.getString(10)), ReviewReader.ts(rs.getObject(11, OffsetDateTime.class))),
                issueId);
    }

    List<RepairEvent> repairEvents(UUID issueId) {
        List<RepairEvent> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT e.id, e.type, e.actor_user_id, u.name, e.department_id, o.name AS dept_name, e.description, e.occurred_at, e.recorded_at
                FROM repair_events e JOIN users u ON u.id = e.actor_user_id LEFT JOIN organizations o ON o.id = e.department_id
                WHERE e.issue_id = ?
                ORDER BY e.recorded_at, CASE e.type WHEN 'REQUESTED' THEN 1 WHEN 'STARTED' THEN 2 WHEN 'COMPLETED' THEN 3 ELSE 4 END, e.id""", issueId)) {
            UUID id = (UUID) r.get("id");
            List<Media> evidence = jdbc.queryForList("SELECT media_id FROM repair_event_media WHERE repair_event_id = ? ORDER BY media_id",
                    UUID.class, id).stream().map(reader::media).toList();
            out.add(new RepairEvent(id, (String) r.get("type"), new Actor((UUID) r.get("actor_user_id"), (String) r.get("name")),
                    r.get("department_id") == null ? null : new Department((UUID) r.get("department_id"), (String) r.get("dept_name")),
                    (String) r.get("description"), instant(r.get("occurred_at")), instant(r.get("recorded_at")), evidence));
        }
        return out;
    }

    List<ReinspectionRequest> requests(UUID issueId) {
        List<ReinspectionRequest> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT id, request_type, status, version, repair_event_id, created_at FROM reinspection_requests WHERE issue_id = ?
                ORDER BY created_at, id""", issueId)) {
            UUID id = (UUID) r.get("id");
            out.add(new ReinspectionRequest(id, (String) r.get("request_type"), (String) r.get("status"), ((Number) r.get("version")).longValue(),
                    (UUID) r.get("repair_event_id"), instant(r.get("created_at")), assignments(id)));
        }
        return out;
    }

    public List<ReinspectionAssignment> assignments(UUID requestId) {
        return jdbc.query("""
                SELECT a.id, a.capture_slot_id, a.patrol_id, p.activity_date, a.assigned_at, a.cancelled_at, a.completed_at
                FROM reinspection_assignments a JOIN patrols p ON p.id = a.patrol_id WHERE a.request_id = ? ORDER BY a.assigned_at, a.id""",
                (rs, n) -> new ReinspectionAssignment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getDate(4).toLocalDate(), ReviewReader.ts(rs.getObject(5, OffsetDateTime.class)),
                        ReviewReader.ts(rs.getObject(6, OffsetDateTime.class)), ReviewReader.ts(rs.getObject(7, OffsetDateTime.class))),
                requestId);
    }

    static java.time.Instant instant(Object v) {
        Object o = ReviewQueryService.offset(v);
        return o == null ? null : ((OffsetDateTime) o).toInstant();
    }

    private static String area(String v) {
        return v == null ? "" : v;
    }
}
