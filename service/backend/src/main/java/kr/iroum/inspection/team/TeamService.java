package kr.iroum.inspection.team;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * 평소 조 편성(기능정의서 ACT-02, 업무 API 5절 /admin/teams).
 * <ul>
 * <li>한 조는 2~3명(자리 1~3). 조원 구성은 적용 시작일(effectiveFrom)부터의 이력(team_memberships)으로 남긴다.</li>
 * <li>바꾸면 그날부터 이전 구성을 닫고 새 구성을 연다. 이미 배정된 순회는 조원을 따로 복사해 두므로 바뀌지 않는다.</li>
 * <li>한 참여자는 같은 기간에 한 조에만 속한다. 다른 조에서 데려오면 그 조에서 빠지며, 그 조가 1명이 되면 거절한다.</li>
 * </ul>
 * service/: 원본에는 없던 기능이다(B-2).
 */
@Service
public class TeamService {

    public record Member(UUID participantId, String name, int seatNo, String status) {
    }

    public record Team(UUID id, String name, UUID organizationId, String organizationName, List<Member> members, LocalDate effectiveFrom,
            long version) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final IdempotencyService idempotency;
    private final AuditLog audit;
    private final Clock clock;

    public TeamService(JdbcTemplate jdbc, AccessPolicy access, IdempotencyService idempotency, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.ACTIVITY_ZONE));
    }

    /** 관할 기관의 활동 중인 조와 기준일(기본 오늘)의 조원. */
    @Transactional(readOnly = true)
    public List<Team> list(AuthenticatedUser user, LocalDate on) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        LocalDate day = on == null ? today() : on;
        List<UUID> ids = jdbc.query(AccessPolicy.scopedOrganizationsCte() + """
                SELECT t.id FROM teams t
                WHERE t.status = 'ACTIVE' AND EXISTS (SELECT 1 FROM scoped sc WHERE sc.program_id = t.program_id AND sc.id = t.organization_id)
                ORDER BY length(t.name), t.name, t.id""",
                (rs, i) -> rs.getObject(1, UUID.class), user.userId(), AccessPolicy.ACTIVITY_MANAGER);
        return ids.stream().map(id -> load(id, day)).toList();
    }

    @Transactional
    public IdempotencyService.Outcome<Team> create(AuthenticatedUser user, UUID organizationId, String name, List<UUID> participantIds,
            LocalDate effectiveFrom, UUID key) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        UUID programId = jdbc.query("SELECT program_id FROM organizations WHERE id = ?", (rs, i) -> rs.getObject(1, UUID.class), organizationId)
                .stream().findFirst().orElseThrow(TeamService::notFound);
        access.requireOrganization(user, AccessPolicy.ACTIVITY_MANAGER, programId, organizationId);
        String cleanName = cleanName(name);
        List<UUID> members = members(participantIds);
        LocalDate from = from(effectiveFrom);
        Map<String, Object> identity = Map.of("organizationId", organizationId, "name", cleanName, "members", members, "from", from.toString());
        return idempotency.execute(user.userId(), "team.create", key, identity, Team.class, 201, () -> {
            UUID teamId = jdbc.queryForObject("INSERT INTO teams (program_id, organization_id, name) VALUES (?, ?, ?) RETURNING id", UUID.class,
                    programId, organizationId, cleanName);
            applyMembers(user, programId, teamId, members, from);
            audit.record(programId, user.userId(), "TEAM_CREATED", "TEAM", teamId, null, null,
                    Map.of("name", cleanName, "members", members, "effectiveFrom", from.toString()));
            return load(teamId, from);
        });
    }

    /** 조 이름·조원 변경. 조원은 effectiveFrom(기본 오늘)부터 새 구성으로 바뀐다. */
    @Transactional
    public Team update(AuthenticatedUser user, UUID teamId, String name, List<UUID> participantIds, LocalDate effectiveFrom, long version) {
        access.requireAnyRole(user, AccessPolicy.ACTIVITY_MANAGER);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT program_id, organization_id, name, version FROM teams WHERE id = ? AND status = 'ACTIVE' FOR UPDATE", teamId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        UUID programId = (UUID) rows.get(0).get("program_id");
        access.requireOrganization(user, AccessPolicy.ACTIVITY_MANAGER, programId, (UUID) rows.get(0).get("organization_id"));
        long current = ((Number) rows.get(0).get("version")).longValue();
        if (current != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.", Map.of("currentVersion", current));
        }
        LocalDate from = from(effectiveFrom);
        Team before = load(teamId, from);
        String cleanName = name == null ? before.name() : cleanName(name);
        if (!cleanName.equals(before.name())) {
            jdbc.update("UPDATE teams SET name = ? WHERE id = ?", cleanName, teamId);
        }
        if (participantIds != null) {
            List<UUID> members = members(participantIds);
            List<UUID> old = before.members().stream().map(Member::participantId).toList();
            if (!members.equals(old)) {
                closeFrom(teamId, null, from);
                applyMembers(user, programId, teamId, members, from);
            }
        }
        jdbc.update("UPDATE teams SET version = version + 1, updated_at = now() WHERE id = ?", teamId);
        Team after = load(teamId, from);
        audit.record(programId, user.userId(), "TEAM_UPDATED", "TEAM", teamId, null, snapshot(before), snapshot(after));
        return after;
    }

    /** 조원을 자리 순서대로 연다. 다른 조에 속한 참여자는 그 조에서 같은 날부터 빠진다. */
    private void applyMembers(AuthenticatedUser user, UUID programId, UUID teamId, List<UUID> members, LocalDate from) {
        for (UUID participantId : members) {
            List<Map<String, Object>> p = jdbc.queryForList("SELECT program_id, organization_id, status FROM participants WHERE id = ?",
                    participantId);
            if (p.isEmpty() || !programId.equals(p.get(0).get("program_id"))
                    || !access.coversOrganization(user, AccessPolicy.ACTIVITY_MANAGER, programId, (UUID) p.get(0).get("organization_id"))) {
                throw notFound();
            }
            if (!"ACTIVE".equals(p.get(0).get("status"))) {
                throw invalid("participantIds", "NOT_ACTIVE", "활동 중인 참여자만 조에 넣을 수 있어요.");
            }
        }
        // 다른 조에서 데려오는 참여자: 그 조에 남는 사람이 1명이면 거절, 0명이면 그 조를 닫는다
        Date d = Date.valueOf(from);
        List<Map<String, Object>> elsewhere = jdbc.queryForList("""
                SELECT DISTINCT m.team_id, t.name FROM team_memberships m JOIN teams t ON t.id = m.team_id
                WHERE m.participant_id = ANY (?) AND m.team_id <> ? AND t.status = 'ACTIVE'
                  AND daterange(m.valid_from, m.valid_until) @> ?::date""", members.toArray(UUID[]::new), teamId, d);
        for (Map<String, Object> e : elsewhere) {
            UUID otherTeam = (UUID) e.get("team_id");
            Integer left = jdbc.queryForObject("""
                    SELECT count(*) FROM team_memberships WHERE team_id = ? AND NOT (participant_id = ANY (?))
                      AND daterange(valid_from, valid_until) @> ?::date""", Integer.class, otherTeam, members.toArray(UUID[]::new), d);
            if (left != null && left == 1) {
                throw invalid("participantIds", "TEAM_WOULD_BE_ALONE",
                        "그러면 " + e.get("name") + "에 1명만 남아요. " + e.get("name") + " 조원을 먼저 바꿔 주세요.");
            }
            for (UUID participantId : members) {
                closeFrom(otherTeam, participantId, from);
            }
            if (left != null && left == 0) {
                jdbc.update("UPDATE teams SET status = 'INACTIVE', version = version + 1, updated_at = now() WHERE id = ?", otherTeam);
            }
        }
        int seat = 1;
        for (UUID participantId : members) {
            jdbc.update("INSERT INTO team_memberships (program_id, team_id, participant_id, seat_no, valid_from) VALUES (?, ?, ?, ?, ?)",
                    programId, teamId, participantId, seat++, Date.valueOf(from));
        }
    }

    /** from부터 조원(또는 한 사람)을 뺀다. from 전에 시작한 이력은 from에 닫고, from 이후에 시작할 예정이던 이력은 지운다. */
    private void closeFrom(UUID teamId, UUID participantId, LocalDate from) {
        Date d = Date.valueOf(from);
        String who = participantId == null ? "" : " AND participant_id = ?";
        Object[] args = participantId == null ? new Object[] { teamId, d } : new Object[] { teamId, d, participantId };
        jdbc.update("DELETE FROM team_memberships WHERE team_id = ? AND valid_from >= ?" + who, args);
        Object[] close = participantId == null ? new Object[] { d, teamId, d } : new Object[] { d, teamId, d, participantId };
        jdbc.update("UPDATE team_memberships SET valid_until = ? WHERE team_id = ? AND (valid_until IS NULL OR valid_until > ?)" + who, close);
    }

    private Team load(UUID teamId, LocalDate on) {
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT t.id, t.name, t.organization_id, o.name AS organization_name, t.version FROM teams t
                JOIN organizations o ON o.program_id = t.program_id AND o.id = t.organization_id WHERE t.id = ?""", teamId);
        List<Member> members = jdbc.query("""
                SELECT m.participant_id, u.name, m.seat_no, p.status, m.valid_from FROM team_memberships m
                JOIN participants p ON p.id = m.participant_id JOIN users u ON u.id = p.user_id
                WHERE m.team_id = ? AND daterange(m.valid_from, m.valid_until) @> ?::date ORDER BY m.seat_no""",
                (rs, i) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4)), teamId, Date.valueOf(on));
        LocalDate since = jdbc.query("""
                SELECT max(valid_from) FROM team_memberships WHERE team_id = ? AND daterange(valid_from, valid_until) @> ?::date""",
                (rs, i) -> rs.getDate(1), teamId, Date.valueOf(on)).stream().filter(java.util.Objects::nonNull).map(Date::toLocalDate)
                .findFirst().orElse(null);
        return new Team(teamId, (String) t.get("name"), (UUID) t.get("organization_id"), (String) t.get("organization_name"), members, since,
                ((Number) t.get("version")).longValue());
    }

    private static Map<String, Object> snapshot(Team t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.name());
        m.put("members", t.members().stream().map(Member::participantId).toList());
        return m;
    }

    private LocalDate from(LocalDate effectiveFrom) {
        LocalDate from = effectiveFrom == null ? today() : effectiveFrom;
        if (from.isBefore(today())) {
            throw invalid("effectiveFrom", "PAST", "지난 날짜부터는 바꿀 수 없어요. 오늘 이후로 골라 주세요.");
        }
        return from;
    }

    private static List<UUID> members(List<UUID> ids) {
        List<UUID> unique = ids == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(ids));
        if (unique.size() < 2 || unique.size() > 3 || unique.size() != ids.size()) {
            throw invalid("participantIds", "TEAM_SIZE", "한 조는 2~3명이에요.");
        }
        return unique;
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 50) {
            throw invalid("name", "INVALID", "조 이름은 1~50자로 넣어 주세요.");
        }
        return n;
    }

    private static ApiException invalid(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, null, List.of(new ErrorResponse.FieldError(field, reason, message)));
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
    }
}
