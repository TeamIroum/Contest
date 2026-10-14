package kr.iroum.inspection.schedule;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * 재점검 구도(기능정의서 ACT-10, 업무 API 5절 POST /admin/patrols/{id}/stops/{stopId}/capture-slots)와 배정 후보 순회.
 * <ul>
 * <li>출발 전 순회의 장소에 재점검 구도(purpose=REINSPECTION)를 더한다. 순회 version·revision을 올려 시니어 앱 동기화에 반영한다.</li>
 * <li>후보: 요청 시설이 들어 있는 담당 범위의 출발 전 순회(오늘 이후). 배정 자체는 원본 ReinspectionService(업무 API 10절)가 한다.</li>
 * <li>참고 사진(SEN-11 재점검 구도 맞추기): 후보와 함께 같은 시설의 승인 사진을 알려 주고, 구도를 만들 때 붙인다. 시니어 앱이 반투명하게 겹쳐 보여 준다.</li>
 * <li>배정을 취소해도 구도는 이력(취소된 배정)이 가리키므로 지우지 않는다. 대신 후보에 빈 재점검 구도(freeSlotId)를 알려 다음 배정에 다시 쓴다.</li>
 * </ul>
 * service/: 원본에는 구도 추가 API가 없어 재점검 요청을 순회에 배정할 구도를 만들 수 없었다(B-2).
 */
@Service
public class ReinspectionSlotService {

    public record Candidate(UUID patrolId, LocalDate activityDate, String teamName, String routeName, Instant scheduledStartAt, UUID stopId,
            int stopSequence, String stopName, long patrolVersion, UUID freeSlotId, UUID referenceMediaId) {
    }

    public record SlotResult(UUID captureSlotId, UUID patrolId, UUID stopId, long patrolVersion) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final IdempotencyService idempotency;
    private final AuditLog audit;
    private final Clock clock;

    public ReinspectionSlotService(JdbcTemplate jdbc, AccessPolicy access, IdempotencyService idempotency, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    /** 재점검 요청을 배정할 수 있는 순회 장소(같은 시설, 출발 전, 담당 범위). 같은 순회에 같은 시설이 여러 번 있으면 장소마다 하나씩. */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(AuthenticatedUser user, UUID requestId) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        List<Map<String, Object>> req = jdbc.queryForList("SELECT program_id, facility_id, status FROM reinspection_requests WHERE id = ?", requestId);
        if (req.isEmpty()) {
            throw notFound();
        }
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.ACTIVITY_ZONE));
        UUID reference = referenceFor(requestId, (UUID) req.get(0).get("facility_id"));
        return jdbc.query(AccessPolicy.scopedOrganizationsCte() + """
                SELECT p.id, p.activity_date, p.team_name_snapshot, p.route_name_snapshot, p.scheduled_start_at, s.id AS stop_id, s.sequence_no,
                       s.name_snapshot, p.version,
                       (SELECT c.id FROM capture_slots c WHERE c.patrol_stop_id = s.id AND c.purpose = 'REINSPECTION' AND c.resolution = 'OPEN'
                          AND c.active_inspection_id IS NULL AND NOT EXISTS (SELECT 1 FROM reinspection_assignments a WHERE a.capture_slot_id = c.id
                          AND a.cancelled_at IS NULL AND a.completed_at IS NULL) ORDER BY c.sequence_no LIMIT 1) AS free_slot_id
                FROM patrols p JOIN patrol_stops s ON s.patrol_id = p.id
                WHERE s.facility_id = ? AND p.program_id = ? AND p.activity_status = 'ASSIGNED' AND p.departed_at IS NULL
                  AND p.departure_confirmed_at IS NULL AND p.activity_date >= ?
                  AND EXISTS (SELECT 1 FROM scoped sc WHERE sc.program_id = p.program_id AND sc.id IN (p.organization_id, p.base_organization_id))
                ORDER BY p.activity_date, p.scheduled_start_at, s.sequence_no""",
                (rs, i) -> new Candidate(rs.getObject("id", UUID.class), rs.getDate("activity_date").toLocalDate(), rs.getString("team_name_snapshot"),
                        rs.getString("route_name_snapshot"), rs.getTimestamp("scheduled_start_at").toInstant(), rs.getObject("stop_id", UUID.class),
                        rs.getInt("sequence_no"), rs.getString("name_snapshot"), rs.getLong("version"), rs.getObject("free_slot_id", UUID.class), reference),
                user.userId(), AccessPolicy.ACTIVITY_MANAGER, req.get(0).get("facility_id"), req.get(0).get("program_id"), java.sql.Date.valueOf(today));
    }

    /**
     * 재점검 구도 맞추기(기능정의서 SEN-11)용 참고 사진: 같은 시설에서 찍혀 개인정보 검사를 통과한(APPROVED) 사진 중
     * 요청의 사건에 연결된 사진을 먼저, 없으면 가장 최근 사진. 없으면 null(시니어 앱은 그림으로 대신한다).
     */
    UUID referenceFor(UUID requestId, UUID facilityId) {
        return jdbc.queryForList("""
                SELECT i.approved_media_id FROM inspections i
                JOIN capture_slots c ON c.id = i.capture_slot_id JOIN patrol_stops ps ON ps.id = c.patrol_stop_id
                JOIN media m ON m.id = i.approved_media_id AND m.privacy_status = 'APPROVED'
                WHERE ps.facility_id = ?
                ORDER BY EXISTS (SELECT 1 FROM issue_inspections ii JOIN reinspection_requests r ON r.issue_id = ii.issue_id
                                 WHERE r.id = ? AND ii.inspection_id = i.id AND ii.unlinked_at IS NULL) DESC, i.captured_at DESC
                LIMIT 1""", UUID.class, facilityId, requestId).stream().findFirst().orElse(null);
    }

    /** 출발 전 순회 장소에 재점검 구도를 더한다. version은 순회 version. referenceMediaId는 같은 시설의 승인 사진만. */
    @Transactional
    public IdempotencyService.Outcome<SlotResult> addSlot(AuthenticatedUser user, UUID patrolId, UUID stopId, String purpose, String label,
            String instruction, boolean required, UUID referenceMediaId, long version, UUID key) {
        UUID programId = access.requireManagedPatrol(user, patrolId);
        if (!"REINSPECTION".equals(purpose)) {
            throw invalid("purpose", "INVALID", "출발 전에 더할 수 있는 구도는 재점검(REINSPECTION)뿐이에요.");
        }
        String cleanLabel = label == null ? "" : label.strip();
        if (cleanLabel.isEmpty() || cleanLabel.length() > 50) {
            throw invalid("label", "INVALID", "구도 이름은 1~50자로 넣어 주세요.");
        }
        String cleanInstruction = instruction == null || instruction.isBlank() ? null : instruction.strip();
        if (cleanInstruction != null && cleanInstruction.length() > 500) {
            throw invalid("instruction", "TOO_LONG", "안내는 500자 이내로 넣어 주세요.");
        }
        Map<String, Object> identity = Map.of("patrolId", patrolId, "stopId", stopId, "label", cleanLabel, "instruction",
                cleanInstruction == null ? "" : cleanInstruction, "required", required, "version", version, "reference",
                referenceMediaId == null ? "" : referenceMediaId.toString());
        return idempotency.execute(user.userId(), "POST /admin/patrols/{id}/stops/{stopId}/capture-slots", key, identity, SlotResult.class, 201, () -> {
            Map<String, Object> p = jdbc.queryForMap(
                    "SELECT version, activity_status, departed_at, departure_confirmed_at FROM patrols WHERE id = ? FOR UPDATE", patrolId);
            if ("CANCELLED".equals(p.get("activity_status"))) {
                throw new ApiException(ErrorCode.PATROL_CANCELLED, "취소된 순회입니다.");
            }
            if (!"ASSIGNED".equals(p.get("activity_status")) || p.get("departed_at") != null || p.get("departure_confirmed_at") != null) {
                throw new ApiException(ErrorCode.ACTIVITY_STATE_CONFLICT, "이미 출발한 순회에는 구도를 더할 수 없어요.",
                        Map.of("reason", "ALREADY_DEPARTED", "activityStatus", p.get("activity_status")));
            }
            long current = ((Number) p.get("version")).longValue();
            if (current != version) {
                throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.", Map.of("currentVersion", current));
            }
            Boolean ownStop = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM patrol_stops WHERE id = ? AND patrol_id = ?)", Boolean.class, stopId,
                    patrolId);
            if (!Boolean.TRUE.equals(ownStop)) {
                throw notFound();
            }
            if (referenceMediaId != null) {
                Boolean sameFacility = jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM inspections i JOIN capture_slots c ON c.id = i.capture_slot_id
                            JOIN patrol_stops ps ON ps.id = c.patrol_stop_id JOIN media m ON m.id = i.approved_media_id
                            WHERE i.approved_media_id = ? AND m.privacy_status = 'APPROVED'
                              AND ps.facility_id = (SELECT facility_id FROM patrol_stops WHERE id = ?))""", Boolean.class, referenceMediaId, stopId);
                if (!Boolean.TRUE.equals(sameFacility)) {
                    throw invalid("referenceMediaId", "INVALID", "참고 사진은 같은 시설의 승인된 사진만 쓸 수 있어요.");
                }
            }
            UUID slotId = jdbc.queryForObject("""
                    INSERT INTO capture_slots (program_id, patrol_stop_id, sequence_no, label, instruction, required, purpose, reference_media_id)
                    VALUES (?, ?, (SELECT coalesce(max(sequence_no), 0) + 1 FROM capture_slots WHERE patrol_stop_id = ?), ?, ?, ?, 'REINSPECTION', ?)
                    RETURNING id""", UUID.class, programId, stopId, stopId, cleanLabel, cleanInstruction, required, referenceMediaId);
            Instant now = clock.instant();
            long next = jdbc.queryForObject(
                    "UPDATE patrols SET version = version + 1, revision = revision + 1, updated_at = ? WHERE id = ? RETURNING version", Long.class,
                    Timestamp.from(now), patrolId);
            audit.record(programId, user.userId(), "REINSPECTION_SLOT_ADDED", "PATROL", patrolId, null, null,
                    Map.of("captureSlotId", slotId, "stopId", stopId, "label", cleanLabel));
            return new SlotResult(slotId, patrolId, stopId, next);
        });
    }

    private static ApiException invalid(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, null, List.of(new ErrorResponse.FieldError(field, reason, message)));
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
    }
}
