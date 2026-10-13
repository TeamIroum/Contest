package kr.iroum.inspection.schema;

import static kr.iroum.inspection.schema.SchemaFixture.END;
import static kr.iroum.inspection.schema.SchemaFixture.START;
import static kr.iroum.inspection.schema.SchemaFixture.ts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.iroum.inspection.TestcontainersConfiguration;

/**
 * 빈 PostgreSQL에 Flyway 마이그레이션을 적용한 뒤 DB 설계 v1.5의 제약을 실제로 확인한다.
 * 실패 사례는 PostgreSQL SQLSTATE로 어떤 종류의 제약이 막았는지까지 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaConstraintTest {

    private static final String CHECK = "23514";
    private static final String UNIQUE = "23505";
    private static final String FOREIGN_KEY = "23503";
    private static final String EXCLUSION = "23P01";

    @Autowired
    JdbcTemplate jdbc;

    SchemaFixture f;

    @BeforeEach
    void setUp() {
        f = new SchemaFixture(jdbc);
    }

    @Test
    void allMigrationsApplyToEmptyDatabase() {
        Integer applied = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isEqualTo(21);
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class);
        assertThat(tables).contains("programs", "users", "patrols", "patrol_members", "capture_slots", "inspections",
                "analysis_results", "review_tasks", "review_decision_issues", "issues", "reinspection_assignments",
                "verifications", "idempotency_requests", "ai_event_inbox");
        assertThat(tables).doesNotContain("priority");
        Integer priorityColumns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'priority'", Integer.class);
        assertThat(priorityColumns).as("D-10·D-13: 우선순위 컬럼 없음").isZero();
    }

    @Test
    void validScenarioFromAssignmentToReviewDecisionIsStorable() {
        UUID kimUser = f.user("김○○");
        UUID parkUser = f.user("박○○");
        UUID kim = f.participant(kimUser);
        UUID park = f.participant(parkUser);
        UUID patrol = f.patrol();
        f.member(patrol, kim, kimUser, 1, START, END);
        f.member(patrol, park, parkUser, 2, START, END);
        UUID stop = f.stop(patrol, f.facility, 1);
        UUID slot = f.slot(stop, 1);
        UUID inspection = f.acceptedInspection(slot, kimUser, 1);
        jdbc.update("UPDATE capture_slots SET accepted_inspection_id = ?, resolution = 'ACCEPTED' WHERE id = ?", inspection, slot);
        jdbc.update("UPDATE patrol_stops SET visit_status = 'CAPTURED' WHERE id = ?", stop);
        // 첫 개인 시작 → IN_PROGRESS, 담당자 출발 확인은 독립 기록
        jdbc.update("""
                UPDATE patrol_members SET started_at = ?, start_received_at = ?, start_source = 'SELF'
                WHERE patrol_id = ? AND participant_id = ?""", ts(START), ts(START), patrol, kim);
        jdbc.update("UPDATE patrols SET activity_status = 'IN_PROGRESS', departed_at = ? WHERE id = ?", ts(START), patrol);

        UUID task = f.reviewTask(stop, f.facility, "INSPECTION:" + inspection);
        jdbc.update("INSERT INTO review_task_inspections (program_id, review_task_id, inspection_id) VALUES (?, ?, ?)", f.program, task, inspection);
        UUID decision = f.id("""
                INSERT INTO review_decisions (program_id, review_task_id, decided_by, decision, reason)
                VALUES (?, ?, ?, 'ISSUE_CONFIRMED', '사진으로 들뜸 확인') RETURNING id""", f.program, task, f.manager);
        UUID issue = f.id("""
                INSERT INTO issues (program_id, facility_id, defect_code, summary, assigned_department_id, source_review_decision_id)
                VALUES (?, ?, 'lifted', '입구 쪽 블록 들뜸', ?, ?) RETURNING id""", f.program, f.facility, f.department, decision);
        jdbc.update("INSERT INTO issue_inspections (program_id, issue_id, inspection_id, linked_by) VALUES (?, ?, ?, ?)",
                f.program, issue, inspection, f.manager);
        jdbc.update("""
                INSERT INTO review_decision_issues (program_id, review_decision_id, sequence_no, inspection_id, issue_id, mode,
                    defect_code_snapshot, summary_snapshot)
                VALUES (?, ?, 1, ?, ?, 'CREATE', 'lifted', '입구 쪽 블록 들뜸')""", f.program, decision, inspection, issue);
        jdbc.update("UPDATE review_tasks SET status = 'RESOLVED' WHERE id = ?", task);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_decision_issues WHERE review_decision_id = ?", Integer.class, decision))
                .isEqualTo(1);
    }

    @Test
    void sameParticipantCannotHaveOverlappingActiveAssignments() {
        UUID user = f.user("김○○");
        UUID kim = f.participant(user);
        UUID first = f.patrol();
        f.member(first, kim, user, 1, START, END);
        UUID overlapping = f.patrol(START.plusSeconds(3600), END.plusSeconds(3600));
        violates(EXCLUSION, () -> f.member(overlapping, kim, user, 1, START.plusSeconds(3600), END.plusSeconds(3600)));

        // 겹치지 않는 시간대 배정은 허용, 재편성으로 빠진(비활성) 배정은 겹침 검사에서 제외
        // 03:00 이후는 첫 배정(01:00~03:00)과도, 아래에서 다시 넣는 02:00~04:00 배정과도 겹치지 않는다.
        Instant laterStart = END.plusSeconds(7200);
        UUID later = f.patrol(laterStart, laterStart.plusSeconds(3600));
        assertThatCode(() -> f.member(later, kim, user, 1, laterStart, laterStart.plusSeconds(3600))).doesNotThrowAnyException();
        jdbc.update("UPDATE patrol_members SET assignment_active = false WHERE patrol_id = ?", first);
        assertThatCode(() -> f.member(overlapping, kim, user, 1, START.plusSeconds(3600), END.plusSeconds(3600))).doesNotThrowAnyException();
    }

    @Test
    void seatsArePositiveAndUniqueWhileActive() {
        // V11: 결석·복귀 확인 조원도 자리를 가지므로 4번 자리를 허용한다. 정원은 이동 때 현장 조원 3명으로 검사한다(D-34).
        UUID patrol = f.patrol();
        UUID u1 = f.user("김○○");
        UUID u2 = f.user("박○○");
        violates(CHECK, () -> f.member(patrol, f.participant(u1), u1, 0, START, END));
        f.member(patrol, f.participant(u2), u2, 1, START, END);
        UUID u3 = f.user("이○○");
        violates(UNIQUE, () -> f.member(patrol, f.participant(u3), u3, 1, START, END));
    }

    @Test
    void memberTimesAndConfirmationsMustBeConsistent() {
        UUID user = f.user("김○○");
        UUID kim = f.participant(user);
        UUID patrol = f.patrol();
        UUID member = f.member(patrol, kim, user, 1, START, END);
        violates(CHECK, () -> jdbc.update("""
                UPDATE patrol_members SET started_at = ?, start_source = 'SELF', returned_at = ?, return_source = 'ADMIN_CORRECTION'
                WHERE id = ?""", ts(END), ts(START), member));
        violates(CHECK, () -> jdbc.update("""
                UPDATE patrol_members SET returned_at = ?, return_source = 'MANAGER_CONFIRMATION', return_confirmed_at = ?
                WHERE id = ?""", ts(END), ts(END), member));
        violates(CHECK, () -> jdbc.update("UPDATE patrol_members SET attendance = 'PRESENT' WHERE id = ?", member));
        // 시각 정정만 있고 복귀 확인 전인 상태는 허용(returned=false)
        assertThatCode(() -> jdbc.update("UPDATE patrol_members SET returned_at = ?, return_source = 'ADMIN_CORRECTION' WHERE id = ?",
                ts(END), member)).doesNotThrowAnyException();
    }

    @Test
    void patrolStatusRequiresMatchingRecords() {
        UUID patrol = f.patrol();
        violates(CHECK, () -> jdbc.update("UPDATE patrols SET activity_status = 'CANCELLED' WHERE id = ?", patrol));
        violates(CHECK, () -> jdbc.update("UPDATE patrols SET activity_status = 'ENDED' WHERE id = ?", patrol));
        violates(CHECK, () -> jdbc.update("UPDATE patrols SET activity_status = 'COMPLETED', finished_at = now() WHERE id = ?", patrol));
        violates(CHECK, () -> jdbc.update("UPDATE patrols SET return_reason = 'NORMAL', abort_reason = 'HEALTH', return_reason_source = 'REQUESTED' WHERE id = ?", patrol));
        violates(CHECK, () -> jdbc.update("UPDATE patrols SET sync_deadline_at = '2026-10-16T00:00:00Z' WHERE id = ?", patrol));
        assertThatCode(() -> jdbc.update("""
                UPDATE patrols SET activity_status = 'CANCELLED', cancelled_at = now(), cancelled_by = ?, cancellation_reason = '우천 해산'
                WHERE id = ?""", f.manager, patrol)).doesNotThrowAnyException();
    }

    @Test
    void endedPatrolRequiresOneImmutableEndRecord() {
        // V9: ENDED ⇔ 종료 기록(트랜잭션 끝 검사). 이전 COMPLETED/ABORTED는 쓸 수 없다.
        UUID patrol = f.patrol();
        violates(CHECK, () -> jdbc.update("UPDATE patrols SET activity_status = 'ENDED', finished_at = now() WHERE id = ?", patrol));
        violates(CHECK, () -> jdbc.update("""
                INSERT INTO patrol_end_records (patrol_id, program_id, ended_at, end_reason, trigger_type, trigger_participant_id, actor_user_id,
                    normal_returned, abort_returned, transferred_out, absent, record_source)
                SELECT id, program_id, now(), 'ALL_RETURNED', 'LAST_RETURN_CONFIRMED', NULL, NULL, 1, 0, 0, 0, 'MIGRATED_RECONSTRUCTED'
                FROM patrols WHERE id = ?""", patrol));
        // LIVE 기록은 계기 조원·수행자가 필요하고, 나간 사람이 1명 이상이어야 한다.
        violates(CHECK, () -> jdbc.update(endAndRecord("'LIVE'", "1"), patrol, patrol));
        violates(CHECK, () -> jdbc.update(endAndRecord("'MIGRATED_RECONSTRUCTED'", "0"), patrol, patrol));
        assertThatCode(() -> jdbc.update(endAndRecord("'MIGRATED_RECONSTRUCTED'", "1"), patrol, patrol)).doesNotThrowAnyException();
        // 종료 기록은 바꾸거나 지울 수 없다(SQLSTATE 55000).
        violates("55000", () -> jdbc.update("UPDATE patrol_end_records SET end_reason = 'ALL_TRANSFERRED' WHERE patrol_id = ?", patrol));
        violates("55000", () -> jdbc.update("DELETE FROM patrol_end_records WHERE patrol_id = ?", patrol));
        // ENDED를 다른 상태로 되돌리면 종료 기록과 맞지 않는다.
        violates(CHECK, () -> jdbc.update("UPDATE patrols SET activity_status = 'IN_PROGRESS', finished_at = NULL WHERE id = ?", patrol));
    }

    /** 한 문장 안에서 종료 기록과 ENDED를 함께 쓴다(지연 검사는 문장 커밋 때). */
    private static String endAndRecord(String source, String normal) {
        return """
                WITH r AS (
                    INSERT INTO patrol_end_records (patrol_id, program_id, ended_at, end_reason, trigger_type, normal_returned, abort_returned,
                        transferred_out, absent, record_source)
                    SELECT id, program_id, now(), 'ALL_RETURNED', 'LAST_RETURN_CONFIRMED', %s, 0, 0, 0, %s FROM patrols WHERE id = ?
                    RETURNING patrol_id)
                UPDATE patrols SET activity_status = 'ENDED', finished_at = now() WHERE id = ? AND EXISTS (SELECT 1 FROM r)""".formatted(normal, source);
    }

    @Test
    void privacyReviewStatesAndOneReleaseInProgressPerMedia() {
        // V14(D-28): 처리 상태별 사유·완료 시각 짝, 부족 판정(ok=false)은 처리 상태 없음, 사진별 진행 중 재승인 하나.
        UUID media = f.media();
        String insert = """
                INSERT INTO privacy_reviews (program_id, media_id, decided_by, ok, reason, media_record_version, decided_media_version,
                    provider_sync_status, release_status, failure_reason, invalid_reason, completed_at, created_at)
                VALUES (?, ?, ?, ?, '재확인', 1, 1, 'PENDING', ?, ?, ?, ?, now())""";
        violates(CHECK, () -> jdbc.update(insert, f.program, media, f.manager, false, "PENDING", null, null, null));
        violates(CHECK, () -> jdbc.update(insert, f.program, media, f.manager, true, "FAILED", null, null, null));
        violates(CHECK, () -> jdbc.update(insert, f.program, media, f.manager, true, "PENDING", "RETRY_EXHAUSTED", null, null));
        violates(CHECK, () -> jdbc.update(insert, f.program, media, f.manager, true, "INVALIDATED", null, null, null));
        violates(CHECK, () -> jdbc.update(insert, f.program, media, f.manager, true, "COMPLETED", null, null, null));
        assertThatCode(() -> jdbc.update(insert, f.program, media, f.manager, false, null, null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update(insert, f.program, media, f.manager, true, "PENDING", null, null, null)).doesNotThrowAnyException();
        violates(UNIQUE, () -> jdbc.update(insert, f.program, media, f.manager, true, "FAILED", "PROVIDER_REJECTED", null, null));
        assertThatCode(() -> jdbc.update(insert, f.program, media, f.manager, true, "INVALIDATED", null, "REMASKED", null))
                .doesNotThrowAnyException();
    }

    @Test
    void skippedSlotMayBeOpenOnlyWhileAPhotoIsProcessing() {
        // V14(D-39): 기준 건너뛰기가 연결된 구도는 처리 중 사진이 있을 때만 OPEN일 수 있다.
        UUID user = f.user("김○○");
        UUID patrol = f.patrol();
        UUID stop = f.stop(patrol, f.facility, 1);
        UUID slot = f.slot(stop, 1);
        UUID event = f.id("""
                INSERT INTO patrol_events (program_id, patrol_id, patrol_stop_id, actor_user_id, type, reason_code, occurred_at)
                VALUES (?, ?, ?, ?, 'STOP_SKIPPED', 'UNSAFE', now()) RETURNING id""", f.program, patrol, stop, user);
        jdbc.update("UPDATE capture_slots SET resolution = 'SKIPPED', skipped_by_event_id = ? WHERE id = ?", event, slot);
        violates(CHECK, () -> jdbc.update("UPDATE capture_slots SET resolution = 'OPEN' WHERE id = ?", slot));
    }

    @Test
    void photoReceiptAndAcceptanceRules() {
        UUID user = f.user("김○○");
        UUID patrol = f.patrol();
        UUID stop = f.stop(patrol, f.facility, 1);
        UUID slot = f.slot(stop, 1);
        violates(CHECK, () -> jdbc.update("""
                INSERT INTO inspections (program_id, capture_slot_id, submitted_by, client_submission_id, attempt_id, request_hash,
                    submission_sha256, attempt_no, captured_at, receipt_status, masking_version, device_masked, device_mask_report_json)
                VALUES (?, ?, ?, gen_random_uuid(), gen_random_uuid(), '\\x00', decode(repeat('00', 32), 'hex'), 1, now(), 'RECEIVED',
                    'v', true, '{}')""", f.program, slot, user));
        UUID first = f.acceptedInspection(slot, user, 1);
        violates(UNIQUE, () -> f.acceptedInspection(slot, user, 2));
        violates(CHECK, () -> jdbc.update("UPDATE capture_slots SET resolution = 'ACCEPTED' WHERE id = ?", slot));

        UUID otherSlot = f.slot(stop, 2);
        violates(FOREIGN_KEY, () -> jdbc.update("UPDATE capture_slots SET accepted_inspection_id = ?, resolution = 'ACCEPTED' WHERE id = ?",
                first, otherSlot));
        violates(CHECK, () -> jdbc.update("UPDATE inspections SET device_masked = false WHERE id = ?", first));
        violates(CHECK, () -> jdbc.update("UPDATE capture_slots SET quality_failure_count = 4 WHERE id = ?", slot));
    }

    @Test
    void crossProgramReferencesAreRejected() {
        SchemaFixture other = new SchemaFixture(jdbc);
        UUID patrol = f.patrol();
        violates(FOREIGN_KEY, () -> f.stop(patrol, other.facility, 1));
    }

    @Test
    void reviewTasksDecisionsAndIssueLinks() {
        UUID user = f.user("김○○");
        UUID patrol = f.patrol();
        UUID stop = f.stop(patrol, f.facility, 1);
        UUID slot = f.slot(stop, 1);
        UUID inspection = f.acceptedInspection(slot, user, 1);

        violates(CHECK, () -> jdbc.update("""
                INSERT INTO review_tasks (program_id, facility_id, type, trigger_reason, trigger_key, assigned_department_id)
                VALUES (?, ?, 'INSPECTION', 'ISSUE_SUSPECTED', 'k-no-stop', ?)""", f.program, f.facility, f.department));
        UUID otherFacility = f.facility(f.department);
        violates(FOREIGN_KEY, () -> f.reviewTask(stop, otherFacility, "k-mismatch"));
        UUID task = f.reviewTask(stop, f.facility, "k-1");
        violates(UNIQUE, () -> f.reviewTask(stop, f.facility, "k-1"));

        // 판정 근거: 정정 ID가 있으면 분류 버전은 1 이상, AI 근거가 없으면 정정·버전도 없음
        violates(CHECK, () -> jdbc.update("""
                INSERT INTO review_decisions (program_id, review_task_id, decided_by, decision, reason, classification_version)
                VALUES (?, ?, ?, 'NO_ISSUE', '사유', 0)""", f.program, task, f.manager));
        violates(CHECK, () -> jdbc.update("""
                INSERT INTO review_decisions (program_id, review_task_id, decided_by, decision, reason)
                VALUES (?, ?, ?, 'FOLLOW_UP_REQUIRED', '사유')""", f.program, task, f.manager));

        UUID issue = f.id("""
                INSERT INTO issues (program_id, facility_id, defect_code, summary, assigned_department_id)
                VALUES (?, ?, 'lifted', '들뜸', ?) RETURNING id""", f.program, f.facility, f.department);
        String link = "INSERT INTO issue_inspections (program_id, issue_id, inspection_id, linked_by) VALUES (?, ?, ?, ?)";
        jdbc.update(link, f.program, issue, inspection, f.manager);
        violates(UNIQUE, () -> jdbc.update(link, f.program, issue, inspection, f.manager));
        jdbc.update("UPDATE issue_inspections SET unlinked_at = now(), unlinked_by = ? WHERE issue_id = ?", f.manager, issue);
        assertThatCode(() -> jdbc.update(link, f.program, issue, inspection, f.manager)).doesNotThrowAnyException();
        violates(CHECK, () -> jdbc.update("UPDATE issues SET status = 'AWAITING_VERIFICATION' WHERE id = ?", issue));
        violates(CHECK, () -> jdbc.update("UPDATE issues SET status = 'CLOSED' WHERE id = ?", issue));
    }

    @Test
    void oneReinspectionRequestStaysWithinOnePatrol() {
        UUID user = f.user("김○○");
        UUID patrolA = f.patrol();
        UUID patrolB = f.patrol(START.plusSeconds(86400), END.plusSeconds(86400));
        UUID slotA = f.slot(f.stop(patrolA, f.facility, 1), 1);
        UUID slotB = f.slot(f.stop(patrolB, f.facility, 1), 1);
        UUID decisionTask = f.reviewTask(f.stop(patrolA, f.facility, 2), f.facility, "k-req");
        UUID decision = f.id("""
                INSERT INTO review_decisions (program_id, review_task_id, decided_by, decision, follow_up_type, reason)
                VALUES (?, ?, ?, 'FOLLOW_UP_REQUIRED', 'REPHOTO', '재촬영 필요') RETURNING id""", f.program, decisionTask, f.manager);
        UUID request = f.id("""
                INSERT INTO reinspection_requests (program_id, facility_id, review_decision_id, request_type, requested_by)
                VALUES (?, ?, ?, 'REPHOTO', ?) RETURNING id""", f.program, f.facility, decision, user);
        String assign = """
                INSERT INTO reinspection_assignments (program_id, request_id, capture_slot_id, patrol_stop_id, patrol_id, assigned_by)
                SELECT ?, ?, s.id, s.patrol_stop_id, ?, ? FROM capture_slots s WHERE s.id = ?""";
        jdbc.update(assign, f.program, request, patrolA, f.manager, slotA);
        violates(EXCLUSION, () -> jdbc.update(assign, f.program, request, patrolB, f.manager, slotB));
        // V8: 구도가 배정한 순회 소속이 아니면 저장할 수 없다(구도 → 장소 → 순회 복합 FK).
        UUID otherDecision = f.id("""
                INSERT INTO review_decisions (program_id, review_task_id, decided_by, decision, follow_up_type, reason)
                VALUES (?, ?, ?, 'FOLLOW_UP_REQUIRED', 'REPHOTO', '재촬영 필요') RETURNING id""", f.program,
                f.reviewTask(f.stop(patrolA, f.facility, 3), f.facility, "k-req-2"), f.manager);
        UUID otherRequest = f.id("""
                INSERT INTO reinspection_requests (program_id, facility_id, review_decision_id, request_type, requested_by)
                VALUES (?, ?, ?, 'REPHOTO', ?) RETURNING id""", f.program, f.facility, otherDecision, user);
        violates(FOREIGN_KEY, () -> jdbc.update(assign, f.program, otherRequest, patrolA, f.manager, slotB));
        UUID stopA = jdbc.queryForObject("SELECT patrol_stop_id FROM capture_slots WHERE id = ?", UUID.class, slotA);
        violates(FOREIGN_KEY, () -> jdbc.update("""
                INSERT INTO reinspection_assignments (program_id, request_id, capture_slot_id, patrol_stop_id, patrol_id, assigned_by)
                VALUES (?, ?, ?, ?, ?, ?)""", f.program, otherRequest, slotB, stopA, patrolA, f.manager));
        violates(CHECK, () -> jdbc.update("""
                INSERT INTO reinspection_requests (program_id, facility_id, request_type, requested_by)
                VALUES (?, ?, 'REPHOTO', ?)""", f.program, f.facility, user));
    }

    @Test
    void idempotencyEventsJobsAndAccountsGuards() {
        UUID user = f.user("김○○");
        String idem = """
                INSERT INTO idempotency_requests (user_id, operation, idempotency_key, request_hash, state, expires_at)
                VALUES (?, 'POST /admin/patrols/{id}/cancellations', ?, '\\x01', 'IN_PROGRESS', now() + interval '1 day')""";
        UUID key = UUID.randomUUID();
        jdbc.update(idem, user, key);
        violates(UNIQUE, () -> jdbc.update(idem, user, key));

        UUID patrol = f.patrol();
        UUID stop = f.stop(patrol, f.facility, 1);
        String event = """
                INSERT INTO patrol_events (program_id, patrol_id, patrol_stop_id, actor_user_id, client_event_id, request_hash, type, reason_code, occurred_at)
                VALUES (?, ?, ?, ?, ?, '\\x01', ?, ?, now())""";
        UUID clientEvent = UUID.randomUUID();
        jdbc.update(event, f.program, patrol, stop, user, clientEvent, "STOP_SKIPPED", "UNSAFE");
        violates(UNIQUE, () -> jdbc.update(event, f.program, patrol, stop, user, clientEvent, "STOP_SKIPPED", "UNSAFE"));
        violates(CHECK, () -> jdbc.update(event, f.program, patrol, stop, user, UUID.randomUUID(), "STOP_SKIPPED", "BORED"));
        // V8: 건너뛰기 사유는 필수다.
        violates(CHECK, () -> jdbc.update(event, f.program, patrol, stop, user, UUID.randomUUID(), "STOP_SKIPPED", null));

        UUID slot = f.slot(stop, 1);
        UUID inspection = f.acceptedInspection(slot, user, 1);
        violates(CHECK, () -> jdbc.update("""
                INSERT INTO analysis_jobs (program_id, inspection_id, media_id, type) VALUES (?, ?, ?, 'ANALYZE')""",
                f.program, inspection, f.media()));
        violates(CHECK, () -> jdbc.update("INSERT INTO users (name, login_id) VALUES ('이○○', 'virtual-admin')"));
    }

    private static void violates(String sqlState, Runnable statement) {
        try {
            statement.run();
        } catch (RuntimeException e) {
            Throwable cause = e;
            while (cause != null && !(cause instanceof SQLException)) {
                cause = cause.getCause();
            }
            assertThat(cause).as("SQL 오류여야 함: " + e).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getSQLState()).as(cause.getMessage()).isEqualTo(sqlState);
            return;
        }
        fail("제약 위반(" + sqlState + ")이 발생해야 함");
    }
}
