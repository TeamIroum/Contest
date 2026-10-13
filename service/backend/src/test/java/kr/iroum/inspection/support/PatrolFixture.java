package kr.iroum.inspection.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.SessionService;

/**
 * 03단계 테스트용 가상 순회. 테스트마다 새 사업을 만들어 서로 간섭하지 않는다.
 * 활동일 2026-10-15, 예정 10:00~12:00 KST(01:00~03:00Z), 장소 3곳(②는 구도 2개), 조원 2~3명, 활동 담당자 1명.
 */
public class PatrolFixture {

    public static final LocalDate DATE = LocalDate.parse("2026-10-15");
    public static final Instant START = Instant.parse("2026-10-15T01:00:00Z");
    public static final Instant END = Instant.parse("2026-10-15T03:00:00Z");

    public final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final PhoneCrypto phones;
    public final UUID program = UUID.randomUUID();
    public final UUID agency;
    public final UUID base;
    public final UUID department;
    public final UUID team;
    public final UUID routeVersion;
    public final UUID manager;
    public final List<UUID> facilities = new ArrayList<>();

    public PatrolFixture(JdbcTemplate jdbc, SessionService sessions, PhoneCrypto phones) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.phones = phones;
        jdbc.update("INSERT INTO programs (id, name) VALUES (?, '가상 사업')", program);
        agency = org("AGENCY", null, "가상 복지관");
        base = org("MEETING_POINT", agency, "가상 경로당");
        department = org("DEPARTMENT", null, "가상 도로관리과");
        manager = user("최○○");
        role(manager, "ACTIVITY_MANAGER", agency);
        team = id("INSERT INTO teams (program_id, organization_id, name) VALUES (?, ?, '1조') RETURNING id", program, agency);
        UUID route = id("INSERT INTO routes (program_id, organization_id, name) VALUES (?, ?, '마을회관 코스') RETURNING id", program, agency);
        routeVersion = id("""
                INSERT INTO route_versions (program_id, route_id, version_no, departure_base_id, return_base_id)
                VALUES (?, ?, 1, ?, ?) RETURNING id""", program, route, base, base);
        for (String type : List.of("SIDEWALK", "DRAIN", "BENCH")) {
            facilities.add(id("""
                    INSERT INTO facilities (program_id, facility_code, type, name, managing_department_id, public_managed)
                    VALUES (?, ?, ?, ?, ?, true) RETURNING id""",
                    program, type.substring(0, 2) + "-" + UUID.randomUUID().toString().substring(0, 8), type, "가상 " + type, department));
        }
    }

    public record Patrol(UUID id, List<UUID> users, List<UUID> participants, List<UUID> stops, List<UUID> slots) {
    }

    public Patrol patrol(int memberCount) {
        return patrol(memberCount, START, END);
    }

    public Patrol patrol(int memberCount, Instant start, Instant end) {
        UUID patrol = id("""
                INSERT INTO patrols (program_id, team_id, route_version_id, organization_id, base_organization_id, activity_date,
                    scheduled_start_at, expected_return_at, team_name_snapshot, route_name_snapshot, base_name_snapshot, sync_deadline_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '1조', '마을회관 코스', '가상 경로당', ?) RETURNING id""",
                program, team, routeVersion, agency, base, DATE, ts(start), ts(end), ts(Instant.parse("2026-10-15T15:00:00Z")));
        List<UUID> users = new ArrayList<>();
        List<UUID> participants = new ArrayList<>();
        for (int seat = 1; seat <= memberCount; seat++) {
            UUID user = user(new String[] {"김○○", "박○○", "이○○"}[seat - 1]);
            role(user, "SENIOR", agency);
            UUID participant = id("INSERT INTO participants (program_id, user_id, organization_id) VALUES (?, ?, ?) RETURNING id",
                    program, user, agency);
            jdbc.update("""
                    INSERT INTO patrol_members (program_id, patrol_id, participant_id, user_id_snapshot, name_snapshot, seat_no, activity_date,
                        scheduled_range) VALUES (?, ?, ?, ?, (SELECT name FROM users WHERE id = ?), ?, ?, tstzrange(?, ?))""",
                    program, patrol, participant, user, user, seat, DATE, ts(start), ts(end));
            users.add(user);
            participants.add(participant);
        }
        List<UUID> stops = new ArrayList<>();
        List<UUID> slots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID stop = id("""
                    INSERT INTO patrol_stops (program_id, patrol_id, facility_id, sequence_no, name_snapshot, type_snapshot, department_snapshot_id,
                        latitude_snapshot, longitude_snapshot, instruction_snapshot)
                    VALUES (?, ?, ?, ?, ?, (SELECT type FROM facilities WHERE id = ?), ?, 37.0001, 127.0001, '가까이 가지 말고 찍어 주세요') RETURNING id""",
                    program, patrol, facilities.get(i), i + 1, "가상 장소 " + (i + 1), facilities.get(i), department);
            stops.add(stop);
            for (int s = 1; s <= (i == 1 ? 2 : 1); s++) {
                slots.add(id("INSERT INTO capture_slots (program_id, patrol_stop_id, sequence_no, label) VALUES (?, ?, ?, ?) RETURNING id",
                        program, stop, s, "구도 " + s));
            }
        }
        return new Patrol(patrol, users, participants, stops, slots);
    }

    public String token(UUID user) {
        long version = jdbc.queryForObject("SELECT auth_version FROM users WHERE id = ?", Long.class, user);
        return "Bearer " + sessions.issue(user, version, "test", "테스트 기기").accessToken();
    }

    private static final java.util.concurrent.atomic.AtomicInteger PHONE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(new java.util.Random().nextInt(5000));

    /** 가상 번호 대역(010-0000-xxxx)에서 겹치지 않는 번호를 넣고 정규화한 값을 돌려준다. */
    public String phone(UUID user) {
        String normalized = "0100000" + String.format("%04d", 5000 + PHONE_SEQ.incrementAndGet() % 5000);
        jdbc.update("UPDATE users SET phone_ciphertext = ?, phone_lookup_hmac = ? WHERE id = ?", phones.encrypt(normalized),
                phones.lookupHmac(normalized), user);
        return normalized;
    }

    public UUID user(String name) {
        return id("INSERT INTO users (name) VALUES (?) RETURNING id", name);
    }

    public void role(UUID user, String role, UUID org) {
        jdbc.update("INSERT INTO user_role_scopes (program_id, user_id, organization_id, role) VALUES (?, ?, ?, ?)", program, user, org, role);
    }

    public UUID org(String type, UUID parent, String name) {
        return id("INSERT INTO organizations (program_id, parent_id, type, name) VALUES (?, ?, ?, ?) RETURNING id", program, parent, type, name);
    }

    public UUID id(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    public static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
