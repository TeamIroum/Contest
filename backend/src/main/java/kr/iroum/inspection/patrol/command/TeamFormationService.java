package kr.iroum.inspection.patrol.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.patrol.PatrolReader.MemberRow;
import kr.iroum.inspection.patrol.PatrolReader.PatrolRow;

/**
 * 조 편성 검사와 출발 전 재편성(D-05, D-29, D-30). 내부 서비스이며 HTTP API는 계약 확정 때 추가한다.
 * <ul>
 * <li>편성 단위(회차): 같은 사업·기관·거점·활동일·예정 시작 시각. 해산(CANCELLED) 순회는 세지 않는다.</li>
 * <li>각 조의 실제 인원(배정 중·ABSENT 아님)은 2~3명. 3인 조는 회차 총인원이 홀수일 때만, 정확히 1개.</li>
 * <li>재편성은 출발 전(ASSIGNED·활동 기록 없음)만 하며 회차 잠금 아래 원자적으로 처리한다. 이전 배정 행·출결 이력은 남긴다.</li>
 * <li>실제 인원이 0명이 되는 순회는 같은 트랜잭션에서 해산한다(1인 출발 금지).</li>
 * </ul>
 */
@Service
public class TeamFormationService {

    public record UnitKey(UUID programId, UUID organizationId, UUID baseOrganizationId, LocalDate activityDate, Instant scheduledStartAt) {
    }

    private final JdbcTemplate jdbc;
    private final PatrolCommandSupport support;
    private final AccessPolicy access;
    private final AuditLog audit;

    public TeamFormationService(JdbcTemplate jdbc, PatrolCommandSupport support, AccessPolicy access, AuditLog audit) {
        this.jdbc = jdbc;
        this.support = support;
        this.access = access;
        this.audit = audit;
    }

    /** 회차의 편성 규칙 위반 목록. 비어 있으면 유효하다. */
    @Transactional(readOnly = true)
    public List<String> violations(UnitKey unit) {
        Map<UUID, Integer> sizes = new TreeMap<>();
        for (UUID patrolId : patrolsOf(unit)) {
            sizes.put(patrolId, (int) support.members(patrolId).stream().filter(m -> !m.absent()).count());
        }
        return check(sizes);
    }

    /**
     * 회차 안 순회들의 실제 조원을 desired대로 바꾼다. 값은 결석이 아닌 참여자 목록이며 빈 목록이면 그 순회를 해산한다.
     * desired에 없는 순회는 그대로 두되 회차 검사에는 포함한다. 위반이면 아무것도 바꾸지 않는다.
     * versions에는 바꾸는 순회마다 담당자가 본 최신 순회 version을 넣는다(ACT-05 "변경 사유·최신 version").
     */
    @Transactional
    public void reassign(AuthenticatedUser actor, UnitKey unit, Map<UUID, List<UUID>> desired, Map<UUID, Long> versions,
            String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "재편성 사유가 필요합니다.");
        }
        access.requireOrganization(actor, AccessPolicy.ACTIVITY_MANAGER, unit.programId(), unit.organizationId());
        // 편성 잠금 키(잠금 단계 0): 같은 회차의 편성·재편성을 직렬화해 3인 조가 동시에 둘 생기지 않게 한다.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", unitLockKey(unit));
        Set<UUID> unitPatrols = new TreeSet<>(patrolsOf(unit));
        if (!unitPatrols.containsAll(desired.keySet())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
        }
        Map<UUID, List<UUID>> before = new LinkedHashMap<>();
        Map<UUID, PatrolRow> rows = new TreeMap<>();
        for (UUID patrolId : new TreeSet<>(desired.keySet())) {
            PatrolRow patrol = support.lock(patrolId);
            Long expected = versions.get(patrolId);
            if (expected == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "재편성할 순회의 version이 필요합니다.");
            }
            support.requireVersion(patrol, expected);
            if (!"ASSIGNED".equals(patrol.status()) || hasActivity(patrolId)) {
                throw new ApiException(ErrorCode.ACTIVITY_ALREADY_RECORDED, "출발한 조는 재편성할 수 없어요.");
            }
            rows.put(patrolId, patrol);
            before.put(patrolId, support.members(patrolId).stream().map(MemberRow::participantId).toList());
        }
        // 1단계: 빠지는 조원(결석자 포함)을 모두 비활성화한 뒤 2단계에서 넣는다(겹침 배타 제약을 순서대로 통과).
        for (UUID patrolId : rows.keySet()) {
            Set<UUID> keep = new TreeSet<>(desired.get(patrolId));
            for (MemberRow m : support.members(patrolId)) {
                if (!keep.contains(m.participantId()) || m.absent()) {
                    jdbc.update("""
                            UPDATE patrol_members SET assignment_active = false, version = version + 1, updated_at = now()
                            WHERE patrol_id = ? AND participant_id = ?""", patrolId, m.participantId());
                }
            }
        }
        try {
            for (Map.Entry<UUID, PatrolRow> entry : rows.entrySet()) {
                UUID patrolId = entry.getKey();
                PatrolRow patrol = entry.getValue();
                Set<UUID> present = new TreeSet<>();
                support.members(patrolId).forEach(m -> present.add(m.participantId()));
                for (UUID participant : desired.get(patrolId)) {
                    if (!present.contains(participant)) {
                        join(patrol, participant, freeSeat(patrolId));
                    }
                }
            }
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "같은 참여자의 시간이 겹치거나 배정할 수 없는 참여자입니다.");
        }
        List<String> problems = violationsWithin(unitPatrols, rows.keySet());
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "조 편성 규칙에 맞지 않습니다: " + String.join(", ", problems));
        }
        for (Map.Entry<UUID, PatrolRow> entry : rows.entrySet()) {
            UUID patrolId = entry.getKey();
            if (desired.get(patrolId).isEmpty()) {
                jdbc.update("""
                        UPDATE patrols SET activity_status = 'CANCELLED', cancelled_at = ?, cancelled_by = ?, cancellation_reason = ?
                        WHERE id = ?""", Timestamp.from(support.now()), actor.userId(), reason, patrolId);
            }
            support.bumpVersion(patrolId);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("participantIds", desired.get(patrolId).stream().map(UUID::toString).toList());
            after.put("cancelled", desired.get(patrolId).isEmpty());
            audit.record(unit.programId(), actor.userId(), "TEAM_REASSIGNED", "PATROL", patrolId, reason,
                    Map.of("participantIds", before.get(patrolId).stream().map(UUID::toString).toList()), after);
        }
    }

    private List<String> violationsWithin(Set<UUID> unitPatrols, Set<UUID> changed) {
        Map<UUID, Integer> sizes = new TreeMap<>();
        for (UUID patrolId : unitPatrols) {
            int size = (int) support.members(patrolId).stream().filter(m -> !m.absent()).count();
            if (size == 0 && changed.contains(patrolId)) {
                continue; // 이 재편성에서 해산할 순회
            }
            sizes.put(patrolId, size);
        }
        return check(sizes);
    }

    static List<String> check(Map<UUID, Integer> sizes) {
        List<String> problems = new ArrayList<>();
        sizes.forEach((patrol, size) -> {
            if (size < 2 || size > 3) {
                problems.add("조 인원 " + size + "명(" + patrol + ")");
            }
        });
        int total = sizes.values().stream().mapToInt(Integer::intValue).sum();
        long threes = sizes.values().stream().filter(s -> s == 3).count();
        long expected = total % 2 == 1 ? 1 : 0;
        if (threes != expected) {
            problems.add("3인 조 " + threes + "개(회차 총인원 " + total + "명이면 " + expected + "개)");
        }
        return problems;
    }

    private List<UUID> patrolsOf(UnitKey unit) {
        return jdbc.queryForList("""
                SELECT id FROM patrols WHERE program_id = ? AND organization_id = ? AND base_organization_id = ? AND activity_date = ?
                  AND scheduled_start_at = ? AND activity_status <> 'CANCELLED' ORDER BY id""", UUID.class,
                unit.programId(), unit.organizationId(), unit.baseOrganizationId(), unit.activityDate(), Timestamp.from(unit.scheduledStartAt()));
    }

    private void join(PatrolRow patrol, UUID participant, int seat) {
        // 해산 순회의 배정은 활성으로 남아 있으므로, 같은 시간대로 다시 배정할 때 그 배정만 비활성화한다(DB 설계 6절).
        jdbc.update("""
                UPDATE patrol_members m SET assignment_active = false, version = m.version + 1, updated_at = now()
                FROM patrols p WHERE p.id = m.patrol_id AND p.activity_status = 'CANCELLED' AND m.participant_id = ? AND m.assignment_active
                  AND m.scheduled_range && tstzrange(?, ?)""", participant, Timestamp.from(patrol.scheduledStartAt()),
                Timestamp.from(patrol.expectedReturnAt()));
        int reactivated = jdbc.update("""
                UPDATE patrol_members SET assignment_active = true, seat_no = ?, version = version + 1, updated_at = now()
                WHERE patrol_id = ? AND participant_id = ?""", seat, patrol.id(), participant);
        if (reactivated == 0) {
            jdbc.update("""
                    INSERT INTO patrol_members (program_id, patrol_id, participant_id, user_id_snapshot, name_snapshot, seat_no, activity_date,
                        scheduled_range)
                    SELECT p.program_id, p.id, pa.id, pa.user_id, u.name, ?, p.activity_date, tstzrange(p.scheduled_start_at, p.expected_return_at)
                    FROM patrols p JOIN participants pa ON pa.id = ? AND pa.program_id = p.program_id AND pa.status = 'ACTIVE'
                    JOIN users u ON u.id = pa.user_id
                    WHERE p.id = ?""", seat, participant, patrol.id());
            Long joined = jdbc.queryForObject("SELECT count(*) FROM patrol_members WHERE patrol_id = ? AND participant_id = ? AND assignment_active",
                    Long.class, patrol.id(), participant);
            if (joined == null || joined == 0) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "배정할 수 없는 참여자입니다.");
            }
        }
    }

    private int freeSeat(UUID patrolId) {
        Set<Integer> taken = new TreeSet<>(jdbc.queryForList(
                "SELECT seat_no::int FROM patrol_members WHERE patrol_id = ? AND assignment_active", Integer.class, patrolId));
        for (int seat = 1; seat <= 3; seat++) {
            if (!taken.contains(seat)) {
                return seat;
            }
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "조 인원이 3명을 넘을 수 없습니다.");
    }

    private boolean hasActivity(UUID patrolId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM patrol_members WHERE patrol_id = ? AND assignment_active
                        AND (started_at IS NOT NULL OR returned_at IS NOT NULL OR return_confirmed_at IS NOT NULL))
                    OR EXISTS (SELECT 1 FROM patrol_stops WHERE patrol_id = ? AND visit_status <> 'PENDING')
                    OR EXISTS (SELECT 1 FROM patrol_events WHERE patrol_id = ? AND type <> 'ATTENDANCE_REQUESTED')""",
                Boolean.class, patrolId, patrolId, patrolId));
    }

    private static String unitLockKey(UnitKey unit) {
        return "formation:" + unit.programId() + ":" + unit.organizationId() + ":" + unit.baseOrganizationId() + ":" + unit.activityDate()
                + ":" + unit.scheduledStartAt();
    }
}
