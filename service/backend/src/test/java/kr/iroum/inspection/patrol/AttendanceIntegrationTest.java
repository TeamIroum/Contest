package kr.iroum.inspection.patrol;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.support.ApiCaller;
import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.MutableClock;
import kr.iroum.inspection.support.PatrolFixture;

/** 출석(D-01)과 결석·완료(D-30) — 시니어 요청 멱등, 담당자 확정·대리 사유·결석 제약, 동시 요청. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class AttendanceIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Autowired
    PhoneCrypto phones;

    @Autowired
    MutableClock clock;

    PatrolFixture f;
    PatrolFixture.Patrol p;
    ApiCaller api;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(FixedClockConfiguration.NOW);
        f = new PatrolFixture(jdbc, sessions, phones);
        p = f.patrol(2);
        api = new ApiCaller(mvc, f);
    }

    @Test
    void seniorAttendanceRequestIsIdempotentAndKeepsFirstTime() throws Exception {
        UUID kim = p.users().get(0);
        UUID event = UUID.randomUUID();
        String body = event(event, "2026-10-15T01:20:00Z");
        String first = api.post(kim, path("/attendance-requests"), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.attendance").value("UNCHECKED"))
                .andExpect(jsonPath("$.attendanceRequestedAt").value("2026-10-15T01:20:00Z"))
                .andExpect(jsonPath("$.attendanceRequestReceivedAt").value("2026-10-15T01:30:00Z"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        assertMatches("AttendanceRequestResult", first);
        // 같은 이벤트 재전송은 최초 결과 그대로(duplicate=true), 다른 내용은 409.
        clock.advance(java.time.Duration.ofMinutes(1));
        api.post(kim, path("/attendance-requests"), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.attendanceRequestReceivedAt").value("2026-10-15T01:30:00Z"))
                .andExpect(jsonPath("$.patrolRevision").value(JsonPath.<Integer>read(first, "$.patrolRevision")));
        api.post(kim, path("/attendance-requests"), event(event, "2026-10-15T01:25:00Z")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 새 이벤트로 다시 눌러도 최초 요청 시각을 유지한다.
        api.post(kim, path("/attendance-requests"), event(UUID.randomUUID(), "2026-10-15T01:29:00Z"))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.attendanceRequestedAt").value("2026-10-15T01:20:00Z"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ? AND type = 'ATTENDANCE_REQUESTED'",
                Long.class, p.id())).isEqualTo(2);
    }

    @Test
    void attendanceTimeMustBeOnActivityDayAndNotInFuture() throws Exception {
        UUID kim = p.users().get(0);
        api.post(kim, path("/attendance-requests"), event(UUID.randomUUID(), "2026-10-15T01:36:00Z"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_ACTIVITY_TIME"));
        api.post(kim, path("/attendance-requests"), event(UUID.randomUUID(), "2026-10-14T14:59:59Z"))
                .andExpect(status().isUnprocessableContent());
        // 허용 오차(5분) 안의 미래와 활동일 첫 순간(00:00 KST)은 받는다.
        api.post(kim, path("/attendance-requests"), event(UUID.randomUUID(), "2026-10-15T01:35:00Z")).andExpect(status().isOk());
        UUID park = p.users().get(1);
        api.post(park, path("/attendance-requests"), event(UUID.randomUUID(), "2026-10-14T15:00:00Z")).andExpect(status().isOk());
        api.post(kim, path("/attendance-requests"), "{\"occurredAt\":\"2026-10-15T01:20:00Z\"}").andExpect(status().isBadRequest());
    }

    @Test
    void managerConfirmsAttendanceWithProxyReasonAndVersion() throws Exception {
        UUID kim = p.users().get(0);
        UUID kimP = p.participants().get(0);
        UUID parkP = p.participants().get(1);
        api.post(kim, path("/attendance-requests"), event(UUID.randomUUID(), "2026-10-15T01:20:00Z")).andExpect(status().isOk());
        String ok = api.put(f.manager, path("/attendance/" + kimP), attendance("PRESENT", null, 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.attendance").value("PRESENT"))
                .andExpect(jsonPath("$.proxyAttendanceReason").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        assertMatches("AttendanceUpdateResult", ok);
        // 요청 없이 PRESENT는 대리 사유 필수(공백만은 없음과 같음).
        api.put(f.manager, path("/attendance/" + parkP), attendance("PRESENT", "   ", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("proxyReason"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("REQUIRED"));
        api.put(f.manager, path("/attendance/" + parkP), attendance("PRESENT", "버튼 조작 어려움, 현장 확인", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.proxyAttendanceReason").value("버튼 조작 어려움, 현장 확인"));
        // 오래된 version은 409와 현재 version.
        api.put(f.manager, path("/attendance/" + parkP), attendance("ABSENT", null, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT")).andExpect(jsonPath("$.details.currentVersion").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action = 'ATTENDANCE_CONFIRMED'",
                Long.class, parkP)).isEqualTo(1);
        // 범위 밖 담당자 404, 시니어 403, 배정 아닌 참여자 404.
        UUID other = f.user("다른 담당자");
        f.role(other, "ACTIVITY_MANAGER", f.org("AGENCY", null, "다른 복지관"));
        api.put(other, path("/attendance/" + parkP), attendance("ABSENT", null, 2)).andExpect(status().isNotFound());
        api.put(kim, path("/attendance/" + parkP), attendance("ABSENT", null, 2)).andExpect(status().isForbidden());
        api.put(f.manager, path("/attendance/" + UUID.randomUUID()), attendance("ABSENT", null, 2)).andExpect(status().isNotFound());
    }

    @Test
    void memberWithActivityCannotBeMarkedAbsent() throws Exception {
        jdbc.update("UPDATE patrol_members SET started_at = ?, start_source = 'SELF' WHERE participant_id = ?",
                PatrolFixture.ts(PatrolFixture.START), p.participants().get(0));
        String body = api.put(f.manager, path("/attendance/" + p.participants().get(0)), attendance("ABSENT", null, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"))
                .andExpect(jsonPath("$.details.reason").value("MEMBER_ALREADY_ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorActivityStateConflict", body);
        // 복귀 기록(시작 없이)만 있어도 마찬가지다.
        jdbc.update("UPDATE patrol_members SET returned_at = ?, return_source = 'ADMIN_CORRECTION' WHERE participant_id = ?",
                PatrolFixture.ts(PatrolFixture.END), p.participants().get(1));
        api.put(f.manager, path("/attendance/" + p.participants().get(1)), attendance("ABSENT", null, 0))
                .andExpect(jsonPath("$.details.reason").value("MEMBER_ALREADY_ACTIVE"));
    }

    @Test
    void absenceEndsOnlyWhenSomeoneLeftAndNeverWithZeroTargets() throws Exception {
        // 김○○ 개인 복귀 확인 뒤 박○○ 결석 확정 → 현장 조원 0명, 복귀한 사람이 있으므로 ENDED(계기 ABSENT_CONFIRMED).
        jdbc.update("UPDATE patrols SET activity_status = 'IN_PROGRESS' WHERE id = ?", p.id());
        jdbc.update("""
                UPDATE patrol_members SET returned_at = ?, return_source = 'MANAGER_CONFIRMATION', return_confirmed_at = now(),
                    return_confirmed_by = ?, return_reason = 'NORMAL' WHERE participant_id = ?""",
                PatrolFixture.ts(PatrolFixture.END), f.manager, p.participants().get(0));
        api.put(f.manager, path("/attendance/" + p.participants().get(1)), attendance("ABSENT", null, 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("ENDED"));
        assertThat(jdbc.queryForObject("SELECT trigger_type || ':' || trigger_participant_id || ':' || absent FROM patrol_end_records WHERE patrol_id = ?",
                String.class, p.id())).isEqualTo("ABSENT_CONFIRMED:" + p.participants().get(1) + ":1");

        // 전원 결석은 나간 사람이 없으므로 끝나지 않는다(ASSIGNED 유지).
        PatrolFixture.Patrol q = f.patrol(2, PatrolFixture.START.plusSeconds(4 * 3600), PatrolFixture.END.plusSeconds(4 * 3600));
        api.put(f.manager, "/api/v1/admin/patrols/" + q.id() + "/attendance/" + q.participants().get(0), attendance("ABSENT", null, 0))
                .andExpect(jsonPath("$.activityStatus").value("ASSIGNED"));
        api.put(f.manager, "/api/v1/admin/patrols/" + q.id() + "/attendance/" + q.participants().get(1), attendance("ABSENT", null, 1))
                .andExpect(jsonPath("$.activityStatus").value("ASSIGNED"));
        // 진행 중이어도 복귀·이동한 사람 없이 대상이 0명이 되면 끝내지 않는다.
        PatrolFixture.Patrol r = f.patrol(2, PatrolFixture.START.plusSeconds(8 * 3600), PatrolFixture.END.plusSeconds(8 * 3600));
        jdbc.update("UPDATE patrols SET activity_status = 'RETURNING' WHERE id = ?", r.id());
        api.put(f.manager, "/api/v1/admin/patrols/" + r.id() + "/attendance/" + r.participants().get(0), attendance("ABSENT", null, 0))
                .andExpect(jsonPath("$.activityStatus").value("RETURNING"));
        api.put(f.manager, "/api/v1/admin/patrols/" + r.id() + "/attendance/" + r.participants().get(1), attendance("ABSENT", null, 1))
                .andExpect(jsonPath("$.activityStatus").value("RETURNING"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_end_records WHERE patrol_id IN (?, ?)", Long.class, q.id(), r.id()))
                .isZero();
    }

    @Test
    void cancelledPatrolRejectsAttendance() throws Exception {
        jdbc.update("""
                UPDATE patrols SET activity_status = 'CANCELLED', cancelled_at = now(), cancelled_by = ?, cancellation_reason = '우천'
                WHERE id = ?""", f.manager, p.id());
        api.post(p.users().get(0), path("/attendance-requests"), event(UUID.randomUUID(), "2026-10-15T01:20:00Z"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PATROL_CANCELLED"));
        api.put(f.manager, path("/attendance/" + p.participants().get(0)), attendance("ABSENT", null, 0))
                .andExpect(jsonPath("$.code").value("PATROL_CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ?", Long.class, p.id())).isZero();
    }

    @Test
    void concurrentDuplicateEventsAndConcurrentVersionedUpdates() throws Exception {
        UUID kim = p.users().get(0);
        String body = event(UUID.randomUUID(), "2026-10-15T01:20:00Z");
        List<String> responses = runConcurrently(4, () -> api.post(kim, path("/attendance-requests"), body).andReturn().getResponse()
                .getContentAsString());
        assertThat(responses).extracting(r -> (Boolean) JsonPath.read(r, "$.duplicate")).containsOnlyOnce(false);
        assertThat(responses).extracting(r -> (String) JsonPath.read(r, "$.attendanceRequestReceivedAt")).containsOnly("2026-10-15T01:30:00Z");

        List<String> updates = runConcurrently(4, () -> String.valueOf(api.put(f.manager, path("/attendance/" + p.participants().get(1)),
                attendance("ABSENT", null, 0)).andReturn().getResponse().getStatus()));
        assertThat(updates).containsOnlyOnce("200").filteredOn(s -> !s.equals("200")).containsOnly("409");
    }

    private List<String> runConcurrently(int n, Callable<String> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> fu : futures) {
            results.add(fu.get());
        }
        pool.shutdown();
        return results;
    }

    @Test
    void absentToPresentCorrectionNeedsReasonEvenWithRequestAndKeepsHistory() throws Exception {
        // 김○○는 출석 요청을 보냈지만 결석으로 잘못 확정됐다.
        api.post(p.users().get(0), path("/attendance-requests"), event(UUID.randomUUID(), "2026-10-15T00:50:00Z")).andExpect(status().isOk());
        api.put(f.manager, path("/attendance/" + p.participants().get(0)), attendance("ABSENT", null, 0)).andExpect(status().isOk());
        clock.advance(java.time.Duration.ofMinutes(1));
        // 요청이 있어도 정정 사유 없이 출석으로 되돌릴 수 없다(D-37). 아무것도 저장하지 않는다.
        String missing = api.put(f.manager, path("/attendance/" + p.participants().get(0)), attendance("PRESENT", null, 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("correctionReason"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorValidationFailed", missing);
        assertThat(attendanceOf(0)).isEqualTo("ABSENT");
        String body = api.put(f.manager, path("/attendance/" + p.participants().get(0)),
                        "{\"attendance\":\"PRESENT\",\"correctionReason\":\"결석 오입력\",\"version\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.correctionReason").value("결석 오입력"))
                .andExpect(jsonPath("$.proxyAttendanceReason").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.pendingReturnConfirmation").value(false))
                .andReturn().getResponse().getContentAsString();
        assertMatches("AttendanceUpdateResult", body);
        assertThat(jdbc.queryForList("""
                SELECT before_attendance || '>' || after_attendance || ':' || coalesce(correction_reason, '-') FROM patrol_attendance_changes
                WHERE participant_id = ? ORDER BY recorded_at, id""", String.class, p.participants().get(0)))
                .containsExactly("UNCHECKED>ABSENT:-", "ABSENT>PRESENT:결석 오입력");

        // 출석 요청이 없으면 정정 사유가 대리 출석 사유 요건도 채운다.
        api.put(f.manager, path("/attendance/" + p.participants().get(1)), attendance("ABSENT", null, 2)).andExpect(status().isOk());
        api.put(f.manager, path("/attendance/" + p.participants().get(1)),
                        "{\"attendance\":\"PRESENT\",\"correctionReason\":\"현장에서 함께 출발함\",\"version\":3}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.proxyAttendanceReason").value("현장에서 함께 출발함"));
        // 결석이 아니던 조원의 확정에는 정정 사유를 요구하지 않는다(새 필수 조건을 늘리지 않음).
        PatrolFixture.Patrol q = f.patrol(2, PatrolFixture.START.plusSeconds(4 * 3600), PatrolFixture.END.plusSeconds(4 * 3600));
        api.put(f.manager, "/api/v1/admin/patrols/" + q.id() + "/attendance/" + q.participants().get(0), attendance("PRESENT", "현장 확인", 0))
                .andExpect(status().isOk()).andExpect(jsonPath("$.correctionReason").value(org.hamcrest.Matchers.nullValue()));
    }

    private String attendanceOf(int index) {
        return jdbc.queryForObject("SELECT attendance FROM patrol_members WHERE participant_id = ?", String.class, p.participants().get(index));
    }

    private String path(String suffix) {
        return (suffix.startsWith("/attendance/") ? "/api/v1/admin/patrols/" : "/api/v1/patrols/") + p.id() + suffix;
    }

    private static String event(UUID id, String occurredAt) {
        return "{\"clientEventId\":\"" + id + "\",\"occurredAt\":\"" + occurredAt + "\"}";
    }

    private static String attendance(String value, String reason, long version) {
        return "{\"attendance\":\"" + value + "\"" + (reason == null ? "" : ",\"proxyReason\":\"" + reason + "\"") + ",\"version\":" + version + "}";
    }
}
