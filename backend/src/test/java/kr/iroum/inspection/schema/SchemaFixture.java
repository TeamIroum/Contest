package kr.iroum.inspection.schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 스키마 검증용 최소 데이터. 테스트마다 새 사업(program)을 만들어 서로 간섭하지 않는다. 모든 값은 가상이다.
 */
class SchemaFixture {

    static final LocalDate DATE = LocalDate.parse("2026-10-15");
    static final Instant START = Instant.parse("2026-10-15T01:00:00Z");
    static final Instant END = Instant.parse("2026-10-15T03:00:00Z");
    static final String DEADLINE = "2026-10-15T15:00:00Z";
    private static final byte[] SHA = HexFormat.of().parseHex("00".repeat(32));

    final JdbcTemplate jdbc;
    final UUID program = UUID.randomUUID();
    final UUID agency;
    final UUID department;
    final UUID base;
    final UUID manager;
    final UUID team;
    final UUID routeVersion;
    final UUID facility;

    SchemaFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.update("INSERT INTO programs (id, name) VALUES (?, '가상 사업')", program);
        agency = org("AGENCY", null);
        department = org("DEPARTMENT", null);
        base = org("MEETING_POINT", agency);
        manager = user("최○○");
        team = id("INSERT INTO teams (program_id, organization_id, name) VALUES (?, ?, '1조') RETURNING id", program, agency);
        UUID route = id("INSERT INTO routes (program_id, organization_id, name) VALUES (?, ?, '마을회관 코스') RETURNING id", program, agency);
        routeVersion = id("""
                INSERT INTO route_versions (program_id, route_id, version_no, departure_base_id, return_base_id)
                VALUES (?, ?, 1, ?, ?) RETURNING id""", program, route, base, base);
        facility = facility(department);
    }

    UUID id(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    UUID org(String type, UUID parent) {
        return id("INSERT INTO organizations (program_id, parent_id, type, name) VALUES (?, ?, ?, '가상 조직') RETURNING id",
                program, parent, type);
    }

    UUID user(String name) {
        return id("INSERT INTO users (name) VALUES (?) RETURNING id", name);
    }

    UUID participant(UUID user) {
        return id("INSERT INTO participants (program_id, user_id, organization_id) VALUES (?, ?, ?) RETURNING id", program, user, agency);
    }

    UUID facility(UUID dept) {
        return id("""
                INSERT INTO facilities (program_id, facility_code, type, name, managing_department_id, public_managed)
                VALUES (?, ?, 'SIDEWALK', '가상 보도블록', ?, true) RETURNING id""",
                program, "SW-" + UUID.randomUUID().toString().substring(0, 8), dept);
    }

    UUID patrol() {
        return patrol(START, END);
    }

    UUID patrol(Instant start, Instant end) {
        return id("""
                INSERT INTO patrols (program_id, team_id, route_version_id, organization_id, base_organization_id, activity_date,
                    scheduled_start_at, expected_return_at, team_name_snapshot, route_name_snapshot, base_name_snapshot, sync_deadline_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '1조', '마을회관 코스', '○○동 경로당', ?::timestamptz) RETURNING id""",
                program, team, routeVersion, agency, base, DATE, ts(start), ts(end), DEADLINE);
    }

    UUID member(UUID patrol, UUID participant, UUID user, int seat, Instant start, Instant end) {
        return id("""
                INSERT INTO patrol_members (program_id, patrol_id, participant_id, user_id_snapshot, name_snapshot, seat_no,
                    activity_date, scheduled_range)
                VALUES (?, ?, ?, ?, '김○○', ?, ?, tstzrange(?, ?)) RETURNING id""",
                program, patrol, participant, user, seat, DATE, ts(start), ts(end));
    }

    UUID stop(UUID patrol, UUID facility, int seq) {
        return id("""
                INSERT INTO patrol_stops (program_id, patrol_id, facility_id, sequence_no, name_snapshot, type_snapshot, department_snapshot_id)
                VALUES (?, ?, ?, ?, '가상 장소', 'SIDEWALK', ?) RETURNING id""", program, patrol, facility, seq, department);
    }

    UUID slot(UUID stop, int seq) {
        return id("INSERT INTO capture_slots (program_id, patrol_stop_id, sequence_no, label) VALUES (?, ?, ?, '전체 모습') RETURNING id",
                program, stop, seq);
    }

    UUID media() {
        return id("""
                INSERT INTO media (program_id, bucket, object_key, purpose, privacy_status, content_type, size_bytes, sha256)
                VALUES (?, 'virtual-bucket', ?, 'INSPECTION', 'APPROVED', 'image/jpeg', 1024, ?) RETURNING id""",
                program, "virtual/" + UUID.randomUUID() + ".jpg", SHA);
    }

    /** 영속 접수·채택된 사진 시도. */
    UUID acceptedInspection(UUID slot, UUID user, int attempt) {
        UUID media = media();
        return id("""
                INSERT INTO inspections (program_id, capture_slot_id, submitted_by, client_submission_id, attempt_id, request_hash,
                    submission_sha256, attempt_no, media_id, approved_media_id, captured_at, received_at, receipt_status,
                    masking_version, device_masked, device_mask_report_json, capture_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', 'web-mask-0.1.0', true, '{"faces":0}', 'ACCEPTED')
                RETURNING id""",
                program, slot, user, UUID.randomUUID(), UUID.randomUUID(), SHA, SHA, attempt, media, media,
                ts(START), ts(START));
    }

    UUID reviewTask(UUID stop, UUID facility, String triggerKey) {
        return id("""
                INSERT INTO review_tasks (program_id, facility_id, patrol_stop_id, type, trigger_reason, trigger_key, assigned_department_id)
                VALUES (?, ?, ?, 'INSPECTION', 'ISSUE_SUSPECTED', ?, ?) RETURNING id""",
                program, facility, stop, triggerKey, department);
    }

    static java.sql.Timestamp ts(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }
}
