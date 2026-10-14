package kr.iroum.inspection.schedule;

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
import kr.iroum.inspection.patrol.command.TeamFormationService;

/**
 * 출발 전 재배정(기능정의서 ACT-05, 업무 API 5절 POST /admin/patrols/{id}/reassignments): 결원 교체·조원 변경·코스 변경.
 * <ul>
 * <li>출발 확인 전이고 활동 기록(시작·사진·건너뛰기 등)이 없는 순회만. 출발 뒤에는 조원·코스를 바꾸지 않는다.</li>
 * <li>조원은 원본 재편성(TeamFormationService.reassign, D-29)으로 바꾼다: 이전 배정 행·출결 이력은 남고, 같은 회차 편성 규칙(2~3명)을 검사한다.</li>
 * <li>코스는 공개된 다른 버전으로 바꾸며 장소·구도를 새로 복사한다. 재점검 배정 이력이 있는 순회는 구도를 지울 수 없어 거절한다.</li>
 * <li>사유와 최신 version이 필요하고, 감사 이력에 남긴다.</li>
 * </ul>
 * service/: 원본에는 재편성 API가 없었다(내부 서비스만, B-2).
 */
@Service
public class PatrolReassignmentService {

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final TeamFormationService formation;
    private final PatrolAssignmentService assignments;
    private final AuditLog audit;

    public PatrolReassignmentService(JdbcTemplate jdbc, AccessPolicy access, TeamFormationService formation, PatrolAssignmentService assignments,
            AuditLog audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.formation = formation;
        this.assignments = assignments;
        this.audit = audit;
    }

    @Transactional
    public PatrolAssignmentService.ScheduledPatrol reassign(AuthenticatedUser user, UUID patrolId, List<UUID> participantIds, UUID routeVersionId,
            String reason, long version) {
        UUID program = access.requireManagedPatrol(user, patrolId);
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > 500) {
            throw invalid("reason", "REQUIRED", "바꾸는 이유를 1~500자로 넣어 주세요.");
        }
        if (participantIds == null && routeVersionId == null) {
            throw invalid("participantIds", "REQUIRED", "바꿀 조원이나 코스를 골라 주세요.");
        }
        Map<String, Object> p = jdbc.queryForMap("""
                SELECT program_id, organization_id, base_organization_id, activity_date, scheduled_start_at, route_version_id, activity_status,
                       departed_at, departure_confirmed_at, version FROM patrols WHERE id = ? FOR UPDATE""", patrolId);
        long current = ((Number) p.get("version")).longValue();
        if (current != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.", Map.of("currentVersion", current));
        }
        if ("CANCELLED".equals(p.get("activity_status"))) {
            throw new ApiException(ErrorCode.PATROL_CANCELLED, "취소된 순회입니다.");
        }
        if (!"ASSIGNED".equals(p.get("activity_status")) || p.get("departed_at") != null || p.get("departure_confirmed_at") != null || hasActivity(patrolId)) {
            throw new ApiException(ErrorCode.ACTIVITY_ALREADY_RECORDED, "출발한 순회는 조원·코스를 바꿀 수 없어요.");
        }
        boolean routeChanged = false;
        UUID base = (UUID) p.get("base_organization_id");
        if (routeVersionId != null && !routeVersionId.equals(p.get("route_version_id"))) {
            changeRoute(program, patrolId, routeVersionId);
            routeChanged = true;
            // 코스를 바꾸면 출발 거점도 새 코스의 거점으로 바뀐다. 조원 교체는 바뀐 거점의 편성 단위에서 한다
            // (예전 거점으로 찾으면 순회가 그 단위에 없어 404가 났다)
            base = jdbc.queryForObject("SELECT base_organization_id FROM patrols WHERE id = ?", UUID.class, patrolId);
        }
        boolean membersChanged = false;
        if (participantIds != null) {
            List<UUID> before = jdbc.queryForList("SELECT participant_id FROM patrol_members WHERE patrol_id = ? AND assignment_active ORDER BY seat_no",
                    UUID.class, patrolId);
            List<UUID> next = participantIds.stream().distinct().toList();
            if (next.size() < 2 || next.size() > 3) {
                throw invalid("participantIds", "TEAM_SIZE", "한 조는 2~3명이에요.");
            }
            for (UUID id : next) {
                List<Map<String, Object>> row = jdbc.queryForList("SELECT program_id, status FROM participants WHERE id = ?", id);
                if (row.isEmpty() || !program.equals(row.get(0).get("program_id"))) {
                    throw new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
                }
                if (!"ACTIVE".equals(row.get(0).get("status"))) {
                    throw invalid("participantIds", "NOT_ACTIVE", "활동 중인 참여자만 넣을 수 있어요.");
                }
            }
            if (!new java.util.HashSet<>(before).equals(new java.util.HashSet<>(next))) {
                TeamFormationService.UnitKey unit = new TeamFormationService.UnitKey(program, (UUID) p.get("organization_id"),
                        base, ((java.sql.Date) p.get("activity_date")).toLocalDate(),
                        ((java.sql.Timestamp) p.get("scheduled_start_at")).toInstant());
                // 원본 재편성: 버전 확인·잠금·편성 규칙·이력·감사·version 올리기를 한다
                formation.reassign(user, unit, Map.of(patrolId, next), Map.of(patrolId, current), why);
                membersChanged = true;
            }
        }
        if (routeChanged && !membersChanged) {
            jdbc.update("UPDATE patrols SET version = version + 1, revision = revision + 1, updated_at = now() WHERE id = ?", patrolId);
        }
        if (routeChanged) {
            audit.record(program, user.userId(), "PATROL_ROUTE_CHANGED", "PATROL", patrolId, why, Map.of("routeVersionId", p.get("route_version_id")),
                    Map.of("routeVersionId", routeVersionId));
        }
        java.time.LocalDate day = ((java.sql.Date) p.get("activity_date")).toLocalDate();
        return assignments.schedule(user, day, day).stream().filter(x -> x.id().equals(patrolId)).findFirst().orElseThrow();
    }

    private void changeRoute(UUID program, UUID patrolId, UUID routeVersionId) {
        List<Map<String, Object>> route = jdbc.queryForList("""
                SELECT r.name, v.departure_base_id, o.name AS base_name FROM route_versions v JOIN routes r ON r.id = v.route_id
                JOIN organizations o ON o.id = v.departure_base_id
                WHERE v.id = ? AND v.program_id = ? AND v.published_at IS NOT NULL AND r.status = 'ACTIVE'""", routeVersionId, program);
        if (route.isEmpty()) {
            throw invalid("routeVersionId", "NOT_PUBLISHED", "코스를 다시 골라 주세요. 닫힌 코스이거나 공개되지 않은 버전이에요.");
        }
        Boolean reinspected = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM reinspection_assignments WHERE patrol_id = ?)", Boolean.class, patrolId);
        if (Boolean.TRUE.equals(reinspected)) {
            throw new ApiException(ErrorCode.ACTIVITY_STATE_CONFLICT, "재점검이 배정된 적 있는 순회는 코스를 바꿀 수 없어요. 순회를 취소하고 새로 배정해 주세요.",
                    Map.of("reasonCode", "REINSPECTION_HISTORY"));
        }
        jdbc.update("DELETE FROM capture_slots WHERE patrol_stop_id IN (SELECT id FROM patrol_stops WHERE patrol_id = ?)", patrolId);
        jdbc.update("DELETE FROM patrol_stops WHERE patrol_id = ?", patrolId);
        jdbc.update("""
                UPDATE patrols SET route_version_id = ?, route_name_snapshot = ?, base_organization_id = ?, base_name_snapshot = ?, updated_at = now()
                WHERE id = ?""", routeVersionId, route.get(0).get("name"), route.get(0).get("departure_base_id"), route.get(0).get("base_name"), patrolId);
        assignments.copyRoute(program, patrolId, routeVersionId);
    }

    /** 원본 재편성과 같은 기준: 시작·복귀 기록, 방문 상태, 출석 요청 밖의 이벤트가 있으면 활동이 있는 것으로 본다. */
    private boolean hasActivity(UUID patrolId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM patrol_members WHERE patrol_id = ? AND assignment_active
                        AND (started_at IS NOT NULL OR returned_at IS NOT NULL OR return_confirmed_at IS NOT NULL))
                    OR EXISTS (SELECT 1 FROM patrol_stops WHERE patrol_id = ? AND visit_status <> 'PENDING')
                    OR EXISTS (SELECT 1 FROM patrol_events WHERE patrol_id = ? AND type <> 'ATTENDANCE_REQUESTED')""",
                Boolean.class, patrolId, patrolId, patrolId));
    }

    private static ApiException invalid(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, null, List.of(new ErrorResponse.FieldError(field, reason, message)));
    }
}
