package kr.iroum.inspection.schedule;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import kr.iroum.inspection.common.config.TimeConfig;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse;
import kr.iroum.inspection.common.idempotency.IdempotencyService;

/**
 * 날짜별 순회 배정(기능정의서 ACT-04, 업무 API 5절 POST /admin/patrols)과 순회 일정 조회.
 * <ul>
 * <li>배정할 때 그날의 실제 조원(평소 조, 활동 중인 참여자)과 공개 코스 버전을 순회에 복사한다. 뒤에 조·코스를 바꿔도 이 순회는 그대로다.</li>
 * <li>같은 참여자가 시간이 겹치는 다른 순회에 배정돼 있으면 거절한다(DB 제약도 함께 막는다). 같은 날 여러 회차는 시간이 겹치지 않으면 된다.</li>
 * <li>복귀 예정은 출발 뒤여야 하고, 출발 시각은 활동일(한국 시간) 안이어야 한다. 지난 날짜에는 배정하지 않는다.</li>
 * </ul>
 * service/: 원본에는 없던 기능이다(B-2).
 */
@Service
public class PatrolAssignmentService {

    public record ScheduledPatrol(UUID id, LocalDate activityDate, UUID teamId, String teamName, UUID routeId, String routeName, int routeVersionNo,
            Instant scheduledStartAt, Instant expectedReturnAt, String activityStatus, List<String> members, int stops, int reinspectionSlots,
            long version, List<UUID> memberIds, UUID routeVersionId, boolean departureConfirmed) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final IdempotencyService idempotency;
    private final AuditLog audit;
    private final Clock clock;

    public PatrolAssignmentService(JdbcTemplate jdbc, AccessPolicy access, IdempotencyService idempotency, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.ACTIVITY_ZONE));
    }

    /** 관할 순회 일정(기본: 오늘부터 14일). 취소한 순회는 빼고 보낸다. */
    @Transactional(readOnly = true)
    public List<ScheduledPatrol> schedule(AuthenticatedUser user, LocalDate from, LocalDate to) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        LocalDate start = from == null ? today() : from;
        LocalDate end = to == null ? start.plusDays(13) : to;
        if (end.isBefore(start) || end.isAfter(start.plusDays(62))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "조회 기간을 확인해 주세요(최대 62일).");
        }
        return jdbc.query(AccessPolicy.scopedOrganizationsCte() + """
                SELECT p.id, p.activity_date, p.team_id, p.team_name_snapshot, v.route_id, p.route_name_snapshot, v.version_no,
                       p.scheduled_start_at, p.expected_return_at, p.activity_status, p.version,
                       (SELECT array_agg(m.name_snapshot ORDER BY m.seat_no) FROM patrol_members m WHERE m.patrol_id = p.id AND m.assignment_active) AS members,
                       (SELECT array_agg(m.participant_id ORDER BY m.seat_no) FROM patrol_members m WHERE m.patrol_id = p.id AND m.assignment_active) AS member_ids,
                       p.route_version_id, p.departure_confirmed_at IS NOT NULL AS departure_confirmed,
                       (SELECT count(*) FROM patrol_stops s WHERE s.patrol_id = p.id) AS stops,
                       (SELECT count(*) FROM capture_slots c JOIN patrol_stops s ON s.id = c.patrol_stop_id
                         WHERE s.patrol_id = p.id AND c.purpose = 'REINSPECTION') AS reinspection_slots
                FROM patrols p JOIN route_versions v ON v.id = p.route_version_id
                WHERE EXISTS (SELECT 1 FROM scoped sc WHERE sc.program_id = p.program_id AND sc.id = p.organization_id)
                  AND p.activity_date BETWEEN ? AND ? AND p.activity_status <> 'CANCELLED'
                ORDER BY p.activity_date, p.scheduled_start_at, p.team_name_snapshot""",
                (rs, i) -> {
                    java.sql.Array names = rs.getArray("members");
                    List<String> members = names == null ? List.of() : List.of((String[]) names.getArray());
                    java.sql.Array idArray = rs.getArray("member_ids");
                    List<UUID> memberIds = idArray == null ? List.of() : List.of((UUID[]) idArray.getArray());
                    return new ScheduledPatrol(rs.getObject("id", UUID.class), rs.getDate("activity_date").toLocalDate(),
                            rs.getObject("team_id", UUID.class), rs.getString("team_name_snapshot"), rs.getObject("route_id", UUID.class),
                            rs.getString("route_name_snapshot"), rs.getInt("version_no"), rs.getTimestamp("scheduled_start_at").toInstant(),
                            rs.getTimestamp("expected_return_at").toInstant(), rs.getString("activity_status"), members, rs.getInt("stops"),
                            rs.getInt("reinspection_slots"), rs.getLong("version"), memberIds, rs.getObject("route_version_id", UUID.class),
                            rs.getBoolean("departure_confirmed"));
                },
                user.userId(), AccessPolicy.ACTIVITY_MANAGER, Date.valueOf(start), Date.valueOf(end));
    }

    @Transactional
    public IdempotencyService.Outcome<ScheduledPatrol> assign(AuthenticatedUser user, LocalDate date, UUID teamId, UUID routeVersionId,
            Instant startAt, Instant returnAt, UUID key) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        Map<String, Object> identity = Map.of("date", date.toString(), "teamId", teamId, "routeVersionId", routeVersionId, "start", startAt.toString(),
                "return", returnAt.toString());
        return idempotency.execute(user.userId(), "patrol.assign", key, identity, ScheduledPatrol.class, 201, () -> {
            if (date.isBefore(today())) {
                throw invalid("activityDate", "PAST", "지난 날짜에는 배정할 수 없어요.");
            }
            if (!startAt.atZone(TimeConfig.ACTIVITY_ZONE).toLocalDate().equals(date)) {
                throw invalid("scheduledStartAt", "OUT_OF_DATE", "출발 시각은 활동일 안이어야 해요.");
            }
            if (!returnAt.isAfter(startAt)) {
                throw invalid("expectedReturnAt", "BEFORE_START", "복귀 예정은 출발 뒤여야 해요.");
            }
            // 조: 관할 기관의 활동 중인 조. 잠가서 같은 조의 동시 배정을 줄 세운다
            List<Map<String, Object>> team = jdbc.queryForList(
                    "SELECT program_id, organization_id, name FROM teams WHERE id = ? AND status = 'ACTIVE' FOR UPDATE", teamId);
            if (team.isEmpty()) {
                throw notFound();
            }
            UUID programId = (UUID) team.get(0).get("program_id");
            UUID orgId = (UUID) team.get(0).get("organization_id");
            access.requireOrganization(user, AccessPolicy.ACTIVITY_MANAGER, programId, orgId);
            // 코스: 같은 사업의 활성 코스, 공개된 버전
            List<Map<String, Object>> route = jdbc.queryForList("""
                    SELECT r.name, v.departure_base_id, o.name AS base_name FROM route_versions v JOIN routes r ON r.id = v.route_id
                    JOIN organizations o ON o.id = v.departure_base_id
                    WHERE v.id = ? AND v.program_id = ? AND v.published_at IS NOT NULL AND r.status = 'ACTIVE'""", routeVersionId, programId);
            if (route.isEmpty()) {
                throw invalid("routeVersionId", "NOT_PUBLISHED", "코스를 다시 골라 주세요. 닫힌 코스이거나 공개되지 않은 버전이에요.");
            }
            // 그날의 실제 조원: 평소 조 구성 중 활동 중인 참여자만
            List<Map<String, Object>> members = jdbc.queryForList("""
                    SELECT m.participant_id, p.user_id, u.name FROM team_memberships m
                    JOIN participants p ON p.id = m.participant_id JOIN users u ON u.id = p.user_id
                    WHERE m.team_id = ? AND daterange(m.valid_from, m.valid_until) @> ?::date AND p.status = 'ACTIVE' AND u.status = 'ACTIVE'
                    ORDER BY m.seat_no""", teamId, Date.valueOf(date));
            if (members.size() < 2) {
                throw invalid("teamId", "TEAM_SHORT", "그날 활동할 수 있는 조원이 2명 이상이어야 해요. 조 편성을 먼저 확인해 주세요.");
            }
            Timestamp s = Timestamp.from(startAt);
            Timestamp e = Timestamp.from(returnAt);
            List<String> busy = jdbc.query("""
                    SELECT DISTINCT m.name_snapshot FROM patrol_members m
                    WHERE m.assignment_active AND m.participant_id = ANY (?) AND m.scheduled_range && tstzrange(?, ?)""",
                    (rs, i) -> rs.getString(1), members.stream().map(m -> m.get("participant_id")).toArray(UUID[]::new), s, e);
            if (!busy.isEmpty()) {
                throw new ApiException(ErrorCode.ACTIVITY_STATE_CONFLICT,
                        String.join(", ", busy) + " 님이 같은 시간에 다른 순회에 배정돼 있어요.", Map.of("reasonCode", "SCHEDULE_OVERLAP"));
            }
            UUID patrolId = jdbc.queryForObject("""
                    INSERT INTO patrols (program_id, team_id, route_version_id, organization_id, base_organization_id, activity_date,
                                         scheduled_start_at, expected_return_at, team_name_snapshot, route_name_snapshot, base_name_snapshot, sync_deadline_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ((?::date + 1)::timestamp AT TIME ZONE 'Asia/Seoul')) RETURNING id""",
                    UUID.class, programId, teamId, routeVersionId, orgId, route.get(0).get("departure_base_id"), Date.valueOf(date), s, e,
                    team.get(0).get("name"), route.get(0).get("name"), route.get(0).get("base_name"), Date.valueOf(date));
            int seat = 1;
            for (Map<String, Object> m : members) {
                jdbc.update("""
                        INSERT INTO patrol_members (program_id, patrol_id, participant_id, user_id_snapshot, name_snapshot, seat_no, activity_date, scheduled_range)
                        VALUES (?, ?, ?, ?, ?, ?, ?, tstzrange(?, ?))""",
                        programId, patrolId, m.get("participant_id"), m.get("user_id"), m.get("name"), seat++, Date.valueOf(date), s, e);
            }
            copyRoute(programId, patrolId, routeVersionId);
            audit.record(programId, user.userId(), "PATROL_ASSIGNED", "PATROL", patrolId, null, null,
                    Map.of("activityDate", date.toString(), "teamId", teamId, "routeVersionId", routeVersionId));
            return schedule(user, date, date).stream().filter(p -> p.id().equals(patrolId)).findFirst().orElseThrow();
        });
    }

    /** 코스 버전의 장소·구도를 배정 당시 시설 정보와 함께 순회에 복사한다. */
    void copyRoute(UUID programId, UUID patrolId, UUID routeVersionId) {
        Map<UUID, UUID> stopIds = new LinkedHashMap<>();
        List<UUID> routeStops = new ArrayList<>();
        jdbc.query("SELECT id FROM route_stops WHERE route_version_id = ? ORDER BY sequence_no", rs -> {
            routeStops.add(rs.getObject(1, UUID.class));
        }, routeVersionId);
        for (UUID routeStopId : routeStops) {
            UUID stopId = jdbc.queryForObject("""
                    INSERT INTO patrol_stops (program_id, patrol_id, source_route_stop_id, facility_id, sequence_no, name_snapshot, type_snapshot,
                                              latitude_snapshot, longitude_snapshot, instruction_snapshot, department_snapshot_id)
                    SELECT ?, ?, s.id, f.id, s.sequence_no, f.name, f.type, f.latitude, f.longitude, s.instruction, f.managing_department_id
                    FROM route_stops s JOIN facilities f ON f.id = s.facility_id WHERE s.id = ? RETURNING id""",
                    UUID.class, programId, patrolId, routeStopId);
            stopIds.put(routeStopId, stopId);
        }
        for (Map.Entry<UUID, UUID> e : stopIds.entrySet()) {
            jdbc.update("""
                    INSERT INTO capture_slots (program_id, patrol_stop_id, source_template_id, sequence_no, label, instruction, reference_media_id, required, purpose)
                    SELECT ?, ?, t.id, t.sequence_no, t.label, t.instruction, t.reference_media_id, t.required, 'REGULAR'
                    FROM route_capture_templates t WHERE t.route_stop_id = ? ORDER BY t.sequence_no""", programId, e.getValue(), e.getKey());
        }
    }

    private static ApiException invalid(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, null, List.of(new ErrorResponse.FieldError(field, reason, message)));
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
    }
}
