package kr.iroum.inspection.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import kr.iroum.inspection.TestcontainersConfiguration;

/**
 * local 프로필에서 빈 DB에 마이그레이션과 가상 seed가 함께 적용되는지, seed가 제약을 지키고 반복 적용에 안전한지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DevSeedTest {

    private static final UUID PATROL = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID KIM = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID KIM_USER = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seedMatchesDemoScenarioBaseData() {
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE success AND type = 'SQL' AND version IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM patrol_members WHERE patrol_id = ? AND assignment_active", PATROL)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM patrol_stops WHERE patrol_id = ?", PATROL)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM capture_slots s JOIN patrol_stops p ON p.id = s.patrol_stop_id WHERE p.patrol_id = ?", PATROL))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT activity_status FROM patrols WHERE id = ?", String.class, PATROL)).isEqualTo("ASSIGNED");
        // seed SQL에는 비밀번호·전화 정보가 없다. 시연 설정이 시연 시니어 2명에게만 가상 번호를 암호화해 넣는다.
        assertThat(count("SELECT count(*) FROM users WHERE password_hash IS NOT NULL")).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM users WHERE phone_ciphertext IS NOT NULL ORDER BY id", UUID.class))
                // service/: 활동 담당자(최○○)에게도 담당 선생님 연락처용 가상 번호를 넣는다(SEN-08)
                .containsExactly(KIM_USER, UUID.fromString("10000000-0000-4000-8000-000000000002"), UUID.fromString("10000000-0000-4000-8000-000000000021"));
        // 시설별 관리 부서가 서로 다르다(시설 담당자 관할 확인용)
        assertThat(count("SELECT count(DISTINCT managing_department_id) FROM facilities WHERE facility_code IN ('SW-0001','DR-0001','BN-0001')"))
                .isEqualTo(3);
    }

    @Test
    void reapplyingSeedIsIdempotent() throws Exception {
        long before = count("SELECT count(*) FROM capture_slots");
        String sql = new ClassPathResource("db/seed/R__dev_seed_demo_scenario.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql);
        assertThat(count("SELECT count(*) FROM capture_slots")).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM patrol_members WHERE patrol_id = ?", PATROL)).isEqualTo(2);
    }

    @Test
    void seededParticipantCannotBeDoubleBookedInOverlappingPatrol() {
        UUID overlapping = jdbc.queryForObject("""
                INSERT INTO patrols (program_id, team_id, route_version_id, organization_id, base_organization_id, activity_date,
                    scheduled_start_at, expected_return_at, team_name_snapshot, route_name_snapshot, base_name_snapshot, sync_deadline_at)
                SELECT program_id, team_id, route_version_id, organization_id, base_organization_id, activity_date,
                    scheduled_start_at + interval '1 hour', expected_return_at + interval '1 hour', team_name_snapshot,
                    route_name_snapshot, base_name_snapshot, sync_deadline_at
                FROM patrols WHERE id = ? RETURNING id""", UUID.class, PATROL);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO patrol_members (program_id, patrol_id, participant_id, user_id_snapshot, name_snapshot, seat_no,
                    activity_date, scheduled_range)
                SELECT program_id, ?, participant_id, user_id_snapshot, name_snapshot, 1, activity_date,
                    tstzrange(lower(scheduled_range) + interval '1 hour', upper(scheduled_range) + interval '1 hour')
                FROM patrol_members WHERE patrol_id = ? AND participant_id = ?""", overlapping, PATROL, KIM))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("patrol_members_participant_id_scheduled_range_excl");
        assertThat(count("SELECT count(*) FROM users WHERE id = ?", KIM_USER)).isEqualTo(1);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
