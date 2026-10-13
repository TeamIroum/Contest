package kr.iroum.inspection.review;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.review.ReviewReader.InspectionRow;
import kr.iroum.inspection.review.ReviewReader.Snapshot;
import kr.iroum.inspection.review.ReviewViews.Actor;
import kr.iroum.inspection.review.ReviewViews.AdminInspection;
import kr.iroum.inspection.review.ReviewViews.Analysis;
import kr.iroum.inspection.review.ReviewViews.DecisionHistory;
import kr.iroum.inspection.review.ReviewViews.Department;
import kr.iroum.inspection.review.ReviewViews.FacilityDetail;
import kr.iroum.inspection.review.ReviewViews.FacilitySummary;
import kr.iroum.inspection.review.ReviewViews.InspectionClassification;
import kr.iroum.inspection.review.ReviewViews.IssueResult;
import kr.iroum.inspection.review.ReviewViews.OpenIssue;
import kr.iroum.inspection.review.ReviewViews.Page;
import kr.iroum.inspection.review.ReviewViews.PatrolRef;
import kr.iroum.inspection.review.ReviewViews.ReviewTask;
import kr.iroum.inspection.review.ReviewViews.ReviewTaskItem;
import kr.iroum.inspection.review.ReviewViews.SlotRef;
import kr.iroum.inspection.review.ReviewViews.StopRef;
import kr.iroum.inspection.review.ReviewViews.TaskClassification;
import kr.iroum.inspection.review.ReviewViews.TaskInspection;

/**
 * 관리자 검토 조회(업무 API 9절, 05단계): 검토 목록·작업 상세·사진 상세. FACILITY_MANAGER만, 시설 대장상 현재 관리 부서가 본인 범위인 시설만
 * (생성 당시 부서가 아니라 현재 관할). 역할 없음 403, 범위 밖·없음 404. 목록은 접수순이며 위험도·등급·우선순위로 정렬하지 않는다.
 * 같은 스냅샷을 읽도록 읽기 전용 REPEATABLE READ로 조회한다. 사진 내용 접근은 /media/{id}/content가 요청마다 다시 검사한다.
 */
@Service
public class ReviewQueryService {

    private static final String NOT_FOUND = "찾을 수 없거나 접근할 수 없는 항목입니다.";

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final ReviewReader reader;

    public ReviewQueryService(JdbcTemplate jdbc, AccessPolicy access, ReviewReader reader) {
        this.jdbc = jdbc;
        this.access = access;
        this.reader = reader;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ReviewTaskItem> list(AuthenticatedUser user, String type, String status, String facilityType, String triggerReason,
            LocalDate from, LocalDate to, Integer page, Integer size) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.max(1, Math.min(100, size));
        String where = """
                FROM review_tasks t JOIN facilities f ON f.id = t.facility_id
                JOIN scoped sc ON sc.program_id = f.program_id AND sc.id = f.managing_department_id
                JOIN patrol_stops ps ON ps.id = t.patrol_stop_id JOIN patrols pa ON pa.id = ps.patrol_id
                WHERE (?::varchar IS NULL OR t.type = ?::varchar) AND (?::varchar IS NULL OR t.status = ?::varchar)
                  AND (?::varchar IS NULL OR f.type = ?::varchar) AND (?::varchar IS NULL OR t.trigger_reason = ?::varchar)
                  AND (?::date IS NULL OR (t.created_at AT TIME ZONE 'Asia/Seoul')::date >= ?::date)
                  AND (?::date IS NULL OR (t.created_at AT TIME ZONE 'Asia/Seoul')::date <= ?::date)""";
        Object[] args = {user.userId(), AccessPolicy.FACILITY_MANAGER, type, type, status, status, facilityType, facilityType, triggerReason,
                triggerReason, from, from, to, to};
        List<Map<String, Object>> rows = jdbc.queryForList(AccessPolicy.scopedOrganizationsCte() + """
                SELECT DISTINCT t.id, t.type, t.status, t.version, t.trigger_reason, t.created_at, f.id AS facility_id, f.facility_code, f.type AS ftype,
                    f.name AS fname, f.area_label, pa.id AS patrol_id, pa.activity_date, ps.id AS stop_id
                """ + where
                + " ORDER BY t.created_at, t.id LIMIT " + s + " OFFSET " + ((long) p * s), args);
        Long total = jdbc.queryForObject(AccessPolicy.scopedOrganizationsCte() + "SELECT count(DISTINCT t.id) " + where, Long.class, args);
        List<ReviewTaskItem> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            List<UUID> inspections = linkedInspections(id);
            boolean hasPhoto = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM review_task_inspections x JOIN inspections i ON i.id = x.inspection_id
                        WHERE x.review_task_id = ? AND i.media_id IS NOT NULL)""", Boolean.class, id));
            String category = null;
            if ("INSPECTION".equals(r.get("type")) && !inspections.isEmpty()) {
                InspectionRow last = reader.inspection(inspections.get(inspections.size() - 1)).orElseThrow();
                List<Snapshot> snaps = reader.snapshots(last.id());
                category = snaps.isEmpty() ? null : reader.analysis(snaps.get(snaps.size() - 1), last.ledgerType()).reviewCategory();
            }
            items.add(new ReviewTaskItem(id, (String) r.get("type"), (String) r.get("status"), ((Number) r.get("version")).longValue(),
                    (String) r.get("trigger_reason"), facilitySummary(r), (UUID) r.get("patrol_id"), r.get("activity_date").toString(),
                    (UUID) r.get("stop_id"), hasPhoto, inspections.size(), category, ReviewReader.ts((OffsetDateTime) offset(r.get("created_at"))),
                    null));
        }
        return new Page<>(items, p, s, total == null ? 0 : total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewTask task(AuthenticatedUser user, UUID taskId) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        Map<String, Object> t = jdbc.queryForList("""
                SELECT t.id, t.type, t.status, t.version, t.trigger_reason, t.created_at, t.facility_id, t.patrol_stop_id FROM review_tasks t
                WHERE t.id = ?""", taskId).stream().findFirst().orElseThrow(ReviewQueryService::notFound);
        access.requireFacility(user, (UUID) t.get("facility_id"));
        UUID stopId = (UUID) t.get("patrol_stop_id");
        if (stopId == null) {
            throw notFound(); // REPAIR_VERIFICATION 작업은 후속 범위(10절)
        }
        Map<String, Object> ctx = jdbc.queryForMap("""
                SELECT f.id, f.facility_code, f.type, f.name, f.area_label, o.id AS dept_id, o.name AS dept_name, pa.id AS patrol_id,
                    pa.activity_date, pa.team_name_snapshot, pa.route_name_snapshot, ps.sequence_no, ps.name_snapshot
                FROM facilities f JOIN organizations o ON o.id = f.managing_department_id
                JOIN patrol_stops ps ON ps.id = ? JOIN patrols pa ON pa.id = ps.patrol_id WHERE f.id = ?""", stopId, t.get("facility_id"));
        List<UUID> inspectionIds = linkedInspections(taskId);
        List<TaskInspection> inspections = new ArrayList<>();
        UUID slotId = null;
        boolean approvedPhoto = false;
        for (UUID id : inspectionIds) {
            InspectionRow i = reader.inspection(id).orElseThrow();
            slotId = i.slotId();
            List<Snapshot> snaps = reader.snapshots(id);
            Analysis analysis = snaps.isEmpty() ? null : reader.analysis(snaps.get(snaps.size() - 1), i.ledgerType());
            InspectionClassification c = reader.classification(i, snaps);
            TaskClassification tc = c == null ? null : new TaskClassification(c.current().source(), c.current().facilityType(),
                    c.current().issueTypes(), c.classificationVersion(), c.current().correctionId());
            ReviewViews.Media media = reader.media(i);
            // 현재 사본(재승인 포함)이 개인정보 승인 상태면 판정 가능. 판정 API와 같은 기준(ReviewReader.REVIEWABLE_INSPECTIONS, REVIEW-06).
            approvedPhoto |= media != null && "APPROVED".equals(media.privacyStatus());
            inspections.add(new TaskInspection(i.id(), i.attemptNo(), reader.capturedBy(i), i.capturedAt(), i.receivedAt(), i.receiptStatus(),
                    i.captureStatus(), i.retakeReason(), media, analysis, tc));
        }
        SlotRef slot = slotId == null ? null : jdbc.queryForObject("SELECT id, label, purpose FROM capture_slots WHERE id = ?",
                (rs, n) -> new SlotRef(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), slotId);
        String type = (String) t.get("type");
        String status = (String) t.get("status");
        return new ReviewTask(taskId, type, status, ((Number) t.get("version")).longValue(), (String) t.get("trigger_reason"),
                allowedDecisions(type, status, approvedPhoto),
                new FacilityDetail((UUID) ctx.get("id"), (String) ctx.get("facility_code"), (String) ctx.get("type"), (String) ctx.get("name"),
                        area(ctx.get("area_label")), new Department((UUID) ctx.get("dept_id"), (String) ctx.get("dept_name"))),
                new PatrolRef((UUID) ctx.get("patrol_id"), ((java.sql.Date) ctx.get("activity_date")).toLocalDate(),
                        (String) ctx.get("team_name_snapshot"), (String) ctx.get("route_name_snapshot")),
                new StopRef(stopId, ((Number) ctx.get("sequence_no")).intValue(), (String) ctx.get("name_snapshot")), slot, inspections,
                openIssues((UUID) t.get("facility_id")), decisions(taskId), ReviewReader.ts((OffsetDateTime) offset(t.get("created_at"))));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminInspection inspection(AuthenticatedUser user, UUID inspectionId) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        InspectionRow i = reader.inspection(inspectionId).orElseThrow(ReviewQueryService::notFound);
        UUID facility = facilityOfStop(i.stopId());
        access.requireFacility(user, facility);
        List<Snapshot> snaps = reader.snapshots(inspectionId);
        List<Analysis> analyses = snaps.stream().map(s -> reader.analysis(s, i.ledgerType())).toList();
        Map<String, Object> f = jdbc.queryForMap("SELECT id, facility_code, type, name, area_label FROM facilities WHERE id = ?", facility);
        return new AdminInspection(i.id(), i.patrolId(), i.stopId(), i.slotId(), i.attemptNo(),
                new FacilitySummary((UUID) f.get("id"), (String) f.get("facility_code"), (String) f.get("type"), (String) f.get("name"),
                        area(f.get("area_label"))),
                reader.capturedBy(i), i.capturedAt(), i.receivedAt(), i.receiptStatus(), i.captureStatus(), i.retakeReason(), i.deviceMasked(),
                reader.media(i), analyses, reader.classification(i, snaps),
                jdbc.queryForList("""
                        SELECT x.review_task_id FROM review_task_inspections x JOIN review_tasks t ON t.id = x.review_task_id
                        WHERE x.inspection_id = ? ORDER BY t.created_at, t.id""", UUID.class, inspectionId),
                jdbc.queryForList("""
                        SELECT DISTINCT ii.issue_id FROM issue_inspections ii WHERE ii.inspection_id = ? AND ii.unlinked_at IS NULL ORDER BY ii.issue_id""",
                        UUID.class, inspectionId));
    }

    /** 판정 버튼 기준(9.1절): 끝난 작업은 없음. 반복 촬영 실패는 추가 확인·촬영 장애 처리 종료. 사진 작업은 승인 사진이 있어야 이상 확인·이상 없음. */
    static List<String> allowedDecisions(String type, String status, boolean approvedPhoto) {
        if ("RESOLVED".equals(status)) {
            return List.of();
        }
        if ("CAPTURE_FAILURE".equals(type)) {
            return List.of("FOLLOW_UP_REQUIRED", "RESOLVE_CAPTURE_PROBLEM");
        }
        return approvedPhoto ? List.of("ISSUE_CONFIRMED", "NO_ISSUE", "FOLLOW_UP_REQUIRED") : List.of("FOLLOW_UP_REQUIRED");
    }

    List<UUID> linkedInspections(UUID taskId) {
        return jdbc.queryForList("""
                SELECT x.inspection_id FROM review_task_inspections x JOIN inspections i ON i.id = x.inspection_id
                WHERE x.review_task_id = ? ORDER BY i.attempt_no, i.id""", UUID.class, taskId);
    }

    UUID facilityOfStop(UUID stopId) {
        return jdbc.queryForObject("SELECT facility_id FROM patrol_stops WHERE id = ?", UUID.class, stopId);
    }

    private List<OpenIssue> openIssues(UUID facilityId) {
        return jdbc.query("""
                SELECT id, status, defect_code, opened_at, summary FROM issues WHERE facility_id = ? AND status <> 'CLOSED'
                ORDER BY opened_at, id""",
                (rs, n) -> new OpenIssue(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        ReviewReader.ts(rs.getObject(4, OffsetDateTime.class)), rs.getString(5)),
                facilityId);
    }

    List<DecisionHistory> decisions(UUID taskId) {
        List<DecisionHistory> out = new ArrayList<>();
        for (Map<String, Object> d : jdbc.queryForList("""
                SELECT d.id, d.decision, d.follow_up_type, d.analysis_result_id, d.classification_correction_id, d.classification_version, d.reason,
                    d.decided_by, u.name, d.decided_at FROM review_decisions d JOIN users u ON u.id = d.decided_by
                WHERE d.review_task_id = ? ORDER BY d.decided_at, d.id""", taskId)) {
            UUID id = (UUID) d.get("id");
            List<IssueResult> results = issueResults(id);
            out.add(new DecisionHistory(id, (String) d.get("decision"), (String) d.get("follow_up_type"), (UUID) d.get("analysis_result_id"),
                    (UUID) d.get("classification_correction_id"), (Integer) d.get("classification_version"), (String) d.get("reason"),
                    new Actor((UUID) d.get("decided_by"), (String) d.get("name")), ReviewReader.ts((OffsetDateTime) offset(d.get("decided_at"))),
                    issueIds(results), results));
        }
        return out;
    }

    List<IssueResult> issueResults(UUID decisionId) {
        return jdbc.query("""
                SELECT mode, inspection_id, issue_id, defect_code_snapshot FROM review_decision_issues WHERE review_decision_id = ?
                ORDER BY sequence_no""",
                (rs, n) -> new IssueResult(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4)),
                decisionId);
    }

    static List<UUID> issueIds(List<IssueResult> results) {
        return List.copyOf(new LinkedHashSet<>(results.stream().map(IssueResult::issueId).toList()));
    }

    private static FacilitySummary facilitySummary(Map<String, Object> r) {
        return new FacilitySummary((UUID) r.get("facility_id"), (String) r.get("facility_code"), (String) r.get("ftype"),
                (String) r.get("fname"), area(r.get("area_label")));
    }

    private static String area(Object v) {
        return v == null ? "" : (String) v;
    }

    /** queryForList는 timestamptz를 Timestamp로 줄 수 있다. */
    public static Object offset(Object v) {
        if (v instanceof java.sql.Timestamp t) {
            return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        return v;
    }

    public static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND);
    }
}
