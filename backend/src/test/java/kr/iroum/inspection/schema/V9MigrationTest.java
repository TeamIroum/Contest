package kr.iroum.inspection.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import kr.iroum.inspection.TestcontainersConfiguration;

/**
 * V9 데이터 이전(설계안 8절): V8까지의 이전 조 단위 기록을 만든 뒤 V9를 적용해 기록이 사라지거나 지어내지지 않는지 확인한다.
 * 같은 컨테이너에 새 데이터베이스를 만들어 V8까지만 올린 뒤 V9를 적용한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class V9MigrationTest {

    private static final Instant T = Instant.parse("2026-10-15T00:00:00Z");

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    JdbcTemplate main;

    @Test
    void legacyGroupReturnRecordsMoveToPersonalAndEndRecordsWithoutLoss() {
        DriverManagerDataSource ds = freshDatabase();
        flyway(ds, "8");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SchemaFixture f = new SchemaFixture(jdbc);
        UUID dept = f.org("DEPARTMENT", null);
        UUID facility = f.facility(dept);

        // A: 이전 COMPLETED. 두 조원 NORMAL 확인, 김○○ 앱 정상 요청, 남은 PENDING 장소 1곳.
        UUID a = f.patrol(at(1), at(3));
        UUID[] am = members(f, a, 2, at(1), at(3));
        confirm(jdbc, a, am[0], at(2) , f.manager);
        confirm(jdbc, a, am[1], at(2).plusSeconds(300), f.manager);
        request(jdbc, f, a, am[0], "NORMAL", at(1).plusSeconds(3000));
        UUID aPending = f.stop(a, facility, 1);
        UUID aCaptured = f.stop(a, facility, 2);
        jdbc.update("UPDATE patrol_stops SET visit_status = 'CAPTURED' WHERE id = ?", aCaptured);
        finish(jdbc, a, "COMPLETED", "NORMAL", null, at(3), f.manager);

        // B: 이전 ABORTED. 둘은 ABORT 확인, 세 번째는 종료 시 결석이었으나 종료 뒤 출석으로 바뀜(감사 이력 있음).
        UUID b = f.patrol(at(4), at(6));
        UUID[] bm = members(f, b, 3, at(4), at(6));
        confirm(jdbc, b, bm[0], at(5), f.manager);
        confirm(jdbc, b, bm[1], at(5).plusSeconds(300), f.manager);
        jdbc.update("UPDATE patrol_members SET attendance = 'PRESENT', attendance_checked_at = ?, attendance_checked_by = ? WHERE id = ?",
                ts(at(6).plusSeconds(600)), f.manager, bm[2]);
        UUID bParticipant = jdbc.queryForObject("SELECT participant_id FROM patrol_members WHERE id = ?", UUID.class, bm[2]);
        jdbc.update("""
                INSERT INTO audit_events (program_id, actor_user_id, action, resource_type, resource_id, before_json, after_json, created_at)
                VALUES (?, ?, 'ATTENDANCE_CONFIRMED', 'PATROL_MEMBER', ?, '{"attendance":"ABSENT"}', '{"attendance":"PRESENT"}', ?)""",
                f.program, f.manager, bParticipant, ts(at(6).plusSeconds(600)));
        finish(jdbc, b, "ABORTED", "ABORT", "HEALTH", at(6), f.manager);

        // C: 진행 중(RETURNING). 한 명의 앱 중도 요청으로 남은 장소가 AUTO_ABORT가 됨. 다른 조원은 시각 정정만 있고 확인 전.
        UUID c = f.patrol(at(7), at(9));
        UUID[] cm = members(f, c, 2, at(7), at(9));
        request(jdbc, f, c, cm[0], "ABORT:HEALTH", at(8));
        jdbc.update("UPDATE patrol_members SET returned_at = ?, return_source = 'ADMIN_CORRECTION' WHERE id = ?", ts(at(8)), cm[1]);
        jdbc.update("""
                UPDATE patrols SET activity_status = 'RETURNING', return_reason = 'ABORT', abort_reason = 'HEALTH', return_reason_source = 'REQUESTED'
                WHERE id = ?""", c);
        UUID cStop = f.stop(c, facility, 1);
        jdbc.update("UPDATE patrol_stops SET visit_status = 'NOT_VISITED', not_visited_reason = 'AUTO_ABORT' WHERE id = ?", cStop);

        // D: 이전 COMPLETED. 종료 뒤 출결이 바뀌었는데 같은 참여자의 다른 배정이 있어 감사 이력과 짝을 지을 수 없음.
        UUID d = f.patrol(at(10), at(12));
        UUID[] dm = members(f, d, 2, at(10), at(12));
        confirm(jdbc, d, dm[0], at(11), f.manager);
        jdbc.update("UPDATE patrol_members SET attendance = 'PRESENT', attendance_checked_at = ?, attendance_checked_by = ? WHERE id = ?",
                ts(at(12).plusSeconds(600)), f.manager, dm[1]);
        UUID other = f.patrol(at(13), at(14));
        jdbc.update("""
                INSERT INTO patrol_members (program_id, patrol_id, participant_id, user_id_snapshot, name_snapshot, seat_no, activity_date, scheduled_range)
                SELECT program_id, ?, participant_id, user_id_snapshot, name_snapshot, 1, activity_date, tstzrange(?, ?) FROM patrol_members WHERE id = ?""",
                other, ts(at(13)), ts(at(14)), dm[1]);
        finish(jdbc, d, "COMPLETED", "NORMAL", null, at(12), f.manager);

        flyway(ds, "9");

        // 상태 통합과 변경 기록
        assertThat(status(jdbc, a)).isEqualTo("ENDED");
        assertThat(status(jdbc, b)).isEqualTo("ENDED");
        assertThat(status(jdbc, c)).isEqualTo("RETURNING");
        assertThat(jdbc.queryForObject("SELECT finished_at FROM patrols WHERE id = ?", Timestamp.class, b).toInstant()).isEqualTo(at(6));
        assertThat(jdbc.queryForList("SELECT before_json ->> 'activityStatus' FROM data_migration_changes WHERE rule = 'TERMINAL_TO_ENDED' AND row_id IN (?, ?, ?) ORDER BY 1",
                String.class, a, b, d)).containsExactly("ABORTED", "COMPLETED", "COMPLETED");

        // 개인 확인 구분: 확인된 조원에게만 복사. 시각 정정만 있는 조원은 비어 있다.
        assertThat(reason(jdbc, am[0])).isEqualTo("NORMAL:-");
        assertThat(reason(jdbc, bm[1])).isEqualTo("ABORT:HEALTH");
        assertThat(reason(jdbc, bm[2])).isEqualTo("-:-");
        assertThat(reason(jdbc, cm[1])).isEqualTo("-:-");
        // 개인 요청값: 보낸 사람 행에만.
        assertThat(requested(jdbc, am[0])).isEqualTo("NORMAL:-");
        assertThat(requested(jdbc, am[1])).isEqualTo("-:-");
        assertThat(requested(jdbc, cm[0])).isEqualTo("ABORT:HEALTH");
        // 조 단위 원본 컬럼은 지우지 않는다.
        assertThat(jdbc.queryForObject("SELECT return_reason || ':' || return_reason_source FROM patrols WHERE id = ?", String.class, c))
                .isEqualTo("ABORT:REQUESTED");

        // 종료 기록: 종료 시점 기준(B의 세 번째 조원은 종료 당시 결석으로 센다), 감사 이력으로 짝을 못 지으면 UNVERIFIED.
        assertThat(end(jdbc, a)).isEqualTo("ALL_RETURNED:LAST_RETURN_CONFIRMED:2:0:0:0:MIGRATED_RECONSTRUCTED");
        assertThat(end(jdbc, b)).isEqualTo("ALL_RETURNED:LAST_RETURN_CONFIRMED:0:2:0:1:MIGRATED_RECONSTRUCTED");
        assertThat(end(jdbc, d)).endsWith(":MIGRATED_UNVERIFIED");
        assertThat(jdbc.queryForObject("SELECT trigger_participant_id FROM patrol_end_records WHERE patrol_id = ?", UUID.class, a))
                .isEqualTo(jdbc.queryForObject("SELECT participant_id FROM patrol_members WHERE id = ?", UUID.class, am[1]));

        // 장소: 종료 조 PENDING → TEAM_ENDED, 채택 장소는 그대로, 진행 중 조의 AUTO_ABORT는 PENDING으로 복원·기록.
        assertThat(stop(jdbc, aPending)).isEqualTo("NOT_VISITED:TEAM_ENDED");
        assertThat(stop(jdbc, aCaptured)).isEqualTo("CAPTURED:-");
        assertThat(stop(jdbc, cStop)).isEqualTo("PENDING:-");
        assertThat(jdbc.queryForList("SELECT rule FROM data_migration_changes WHERE row_id IN (?, ?) ORDER BY rule", String.class, aPending, cStop))
                .containsExactly("ACTIVE_AUTO_ABORT_STOP_REOPENED", "ENDED_PENDING_STOP");

        // 백필 뒤에는 이전 상태값을 쓸 수 없다.
        assertThatThrownBy(() -> jdbc.update("UPDATE patrols SET activity_status = 'COMPLETED' WHERE id = ?", c))
                .hasMessageContaining("patrols_activity_status_check");
    }

    @Test
    void v12BackfillsPersonalPauseFromEventsAndLinksEvents() {
        DriverManagerDataSource ds = freshDatabase();
        flyway(ds, "11");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SchemaFixture f = new SchemaFixture(jdbc);
        UUID dept = f.org("DEPARTMENT", null);
        UUID facility = f.facility(dept);
        UUID p = f.patrol(at(1), at(3));
        UUID[] m = members(f, p, 2, at(1), at(3));
        // 이전 조 단위 쉬기 값은 true지만 실제로 누른 사람은 첫 조원뿐이다.
        jdbc.update("UPDATE patrols SET activity_status = 'IN_PROGRESS', paused = true WHERE id = ?", p);
        UUID first = jdbc.queryForObject("SELECT user_id_snapshot FROM patrol_members WHERE id = ?", UUID.class, m[0]);
        jdbc.update("""
                INSERT INTO patrol_events (program_id, patrol_id, actor_user_id, type, occurred_at, received_at)
                VALUES (?, ?, ?, 'PAUSE_STARTED', ?, ?)""", f.program, p, first, ts(at(2)), ts(at(2)));
        UUID stop = f.stop(p, facility, 1);
        UUID slot = f.slot(stop, 1);
        UUID skip = jdbc.queryForObject("""
                INSERT INTO patrol_events (program_id, patrol_id, patrol_stop_id, actor_user_id, type, reason_code, occurred_at, received_at)
                VALUES (?, ?, ?, ?, 'STOP_SKIPPED', 'UNSAFE', ?, ?) RETURNING id""", UUID.class, f.program, p, stop, first, ts(at(2)), ts(at(2)));
        jdbc.update("UPDATE patrol_stops SET visit_status = 'SKIPPED' WHERE id = ?", stop);
        jdbc.update("UPDATE capture_slots SET resolution = 'SKIPPED' WHERE id = ?", slot);

        flyway(ds, "12");

        assertThat(jdbc.queryForList("SELECT paused FROM patrol_members WHERE patrol_id = ? ORDER BY seat_no", Boolean.class, p))
                .containsExactly(true, false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ? AND participation_id IS NULL", Long.class, p))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT skipped_by_event_id FROM capture_slots WHERE id = ?", UUID.class, slot)).isEqualTo(skip);
    }

    @Test
    void v13MovesAPastIntervalReturnRequestOffARejoinedMemberRow() {
        DriverManagerDataSource ds = freshDatabase();
        flyway(ds, "12");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SchemaFixture f = new SchemaFixture(jdbc);
        UUID a = f.patrol(at(1), at(3));
        UUID b = f.patrol(at(1), at(3));
        UUID user = f.user("김○○");
        UUID participant = f.participant(user);
        UUID row = f.member(a, participant, user, 1, at(1), at(3));
        jdbc.update("UPDATE patrols SET activity_status = 'IN_PROGRESS' WHERE id IN (?, ?)", a, b);
        // A 첫 구간에서 NORMAL 요청(01:10), 01:30에 B로 이동, 01:40에 A로 재합류(이전 코드처럼 배정 행 요청값이 남아 있음).
        jdbc.update("""
                UPDATE patrol_members SET requested_return_reason = 'NORMAL', return_requested_at = ?, return_request_received_at = ? WHERE id = ?""",
                ts(at(1).plusSeconds(600)), ts(at(1).plusSeconds(600)), row);
        jdbc.update("UPDATE patrol_members SET assignment_active = false WHERE id = ?", row);
        f.member(b, participant, user, 1, at(1), at(3));
        UUID out = jdbc.queryForObject("""
                INSERT INTO patrol_member_transfers (program_id, participant_id, from_patrol_id, to_patrol_id, transferred_at, transferred_by, reason,
                    from_version, to_version) VALUES (?, ?, ?, ?, ?, ?, '합류', 0, 0) RETURNING id""",
                UUID.class, f.program, participant, a, b, ts(at(1).plusSeconds(1800)), f.manager);
        jdbc.update("""
                UPDATE patrol_participations SET leave_reason = 'TRANSFERRED', left_at = ?, transfer_out_id = ?
                WHERE patrol_id = ? AND participant_id = ?""", ts(at(1).plusSeconds(1800)), out, a, participant);
        jdbc.update("UPDATE patrol_members SET assignment_active = false WHERE patrol_id = ? AND participant_id = ?", b, participant);
        UUID back = jdbc.queryForObject("""
                INSERT INTO patrol_member_transfers (program_id, participant_id, from_patrol_id, to_patrol_id, transferred_at, transferred_by, reason,
                    from_version, to_version) VALUES (?, ?, ?, ?, ?, ?, '복귀 합류', 0, 0) RETURNING id""",
                UUID.class, f.program, participant, b, a, ts(at(1).plusSeconds(2400)), f.manager);
        jdbc.update("UPDATE patrol_members SET assignment_active = true WHERE id = ?", row);
        jdbc.update("UPDATE patrol_participations SET joined_at = ?, transfer_in_id = ? WHERE patrol_id = ? AND participant_id = ? AND leave_reason IS NULL",
                ts(at(1).plusSeconds(2400)), back, a, participant);

        flyway(ds, "13");

        // 배정 행은 지금 구간 요청만(없음), 이전 구간 요청은 닫힌 구간 행에 남고 변경은 기록된다.
        assertThat(jdbc.queryForObject("SELECT requested_return_reason FROM patrol_members WHERE id = ?", String.class, row)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT requested_return_reason FROM patrol_participations WHERE patrol_id = ? AND participant_id = ? AND leave_reason = 'TRANSFERRED'""",
                String.class, a, participant)).isEqualTo("NORMAL");
        assertThat(jdbc.queryForObject("SELECT before_json ->> 'requestedReturnReason' FROM data_migration_changes WHERE row_id = ?", String.class, row))
                .isEqualTo("NORMAL");
    }

    private DriverManagerDataSource freshDatabase() {
        String name = "mig_v9_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        main.execute("CREATE DATABASE " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        return new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
    }

    private static void flyway(DriverManagerDataSource ds, String target) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load().migrate();
    }

    private static Instant at(int hours) {
        return T.plusSeconds(hours * 3600L);
    }

    private static UUID[] members(SchemaFixture f, UUID patrol, int count, Instant start, Instant end) {
        UUID[] ids = new UUID[count];
        for (int i = 0; i < count; i++) {
            UUID user = f.user("김○○");
            ids[i] = f.member(patrol, f.participant(user), user, i + 1, start, end);
        }
        return ids;
    }

    private static void confirm(JdbcTemplate jdbc, UUID patrol, UUID member, Instant at, UUID manager) {
        jdbc.update("""
                UPDATE patrol_members SET returned_at = ?, return_source = 'MANAGER_CONFIRMATION', return_confirmed_at = ?, return_confirmed_by = ?
                WHERE id = ?""", ts(at), ts(at), manager, member);
    }

    private static void request(JdbcTemplate jdbc, SchemaFixture f, UUID patrol, UUID member, String code, Instant at) {
        UUID user = jdbc.queryForObject("SELECT user_id_snapshot FROM patrol_members WHERE id = ?", UUID.class, member);
        jdbc.update("""
                INSERT INTO patrol_events (program_id, patrol_id, actor_user_id, type, reason_code, occurred_at, received_at)
                VALUES (?, ?, ?, 'RETURN_REQUESTED', ?, ?, ?)""", f.program, patrol, user, code, ts(at), ts(at.plusSeconds(5)));
    }

    private static void finish(JdbcTemplate jdbc, UUID patrol, String status, String reason, String abort, Instant at, UUID manager) {
        jdbc.update("""
                UPDATE patrols SET activity_status = ?, finished_at = ?, return_reason = ?, abort_reason = ?, return_reason_source = 'CONFIRMED',
                    return_reason_confirmed_at = ?, return_reason_confirmed_by = ? WHERE id = ?""",
                status, ts(at), reason, abort, ts(at), manager, patrol);
    }

    private static String status(JdbcTemplate jdbc, UUID patrol) {
        return jdbc.queryForObject("SELECT activity_status FROM patrols WHERE id = ?", String.class, patrol);
    }

    private static String reason(JdbcTemplate jdbc, UUID member) {
        return jdbc.queryForObject("SELECT coalesce(return_reason, '-') || ':' || coalesce(abort_reason, '-') FROM patrol_members WHERE id = ?",
                String.class, member);
    }

    private static String requested(JdbcTemplate jdbc, UUID member) {
        return jdbc.queryForObject("""
                SELECT coalesce(requested_return_reason, '-') || ':' || coalesce(requested_abort_reason, '-') FROM patrol_members WHERE id = ?""",
                String.class, member);
    }

    private static String end(JdbcTemplate jdbc, UUID patrol) {
        return jdbc.queryForObject("""
                SELECT end_reason || ':' || trigger_type || ':' || normal_returned || ':' || abort_returned || ':' || transferred_out || ':'
                    || absent || ':' || record_source FROM patrol_end_records WHERE patrol_id = ?""", String.class, patrol);
    }

    private static String stop(JdbcTemplate jdbc, UUID stop) {
        return jdbc.queryForObject("SELECT visit_status || ':' || coalesce(not_visited_reason, '-') FROM patrol_stops WHERE id = ?",
                String.class, stop);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
