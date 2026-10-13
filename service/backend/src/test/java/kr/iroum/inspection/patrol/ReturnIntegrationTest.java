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

/** 개인별 복귀 요청(D-32·D-33)과 담당자 복귀 확인(D-24·D-30·D-37): 상태 경계, 멱등 재전송, 결석 제외 종료, 종료 기록 불변, 종료 후 보완, 동시 확인. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class ReturnIntegrationTest {

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
    ApiCaller api;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(FixedClockConfiguration.NOW);
        f = new PatrolFixture(jdbc, sessions, phones);
        api = new ApiCaller(mvc, f);
    }

    @Test
    void personalRequestsDoNotEndTeammatesAndRetransmissionReplaysRegardlessOfState() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        UUID kim = p.users().get(0);
        UUID park = p.users().get(1);
        // ASSIGNED: 409 NOT_STARTED, 이력·상태 없음(D-32).
        String notStarted = api.post(kim, ret(p), request(UUID.randomUUID(), "NORMAL", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("NOT_STARTED"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorActivityStateConflict", notStarted);
        assertThat(events(p, "RETURN_REQUESTED")).isZero();
        start(p, 0);
        // 김○○ 본인 요청: 박○○는 요청하지 않았으므로 조는 IN_PROGRESS 그대로(D-33).
        UUID event = UUID.randomUUID();
        String first = api.post(kim, ret(p), request(event, "NORMAL", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.returnReason").value("NORMAL"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ReturnResult", first);
        // 현장 조원 전원이 요청하면 RETURNING. 구분은 사람마다 따로 남는다.
        api.post(park, ret(p), request(UUID.randomUUID(), "ABORT", "HEALTH")).andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("RETURNING")).andExpect(jsonPath("$.abortReason").value("HEALTH"));
        assertThat(jdbc.queryForList("SELECT requested_return_reason FROM patrol_members WHERE patrol_id = ? ORDER BY seat_no", String.class,
                p.id())).containsExactly("NORMAL", "ABORT");
        // 개인 요청으로 남은 장소를 미방문 처리하지 않는다.
        assertThat(jdbc.queryForList("SELECT DISTINCT visit_status FROM patrol_stops WHERE patrol_id = ?", String.class, p.id()))
                .containsOnly("PENDING");
        // 다시 보낸 다른 요청은 이력만: 최초 요청값 유지.
        api.post(kim, ret(p), request(UUID.randomUUID(), "ABORT", "DEVICE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.returnReason").value("NORMAL")).andExpect(jsonPath("$.abortReason").doesNotExist());
        // 전원 확인 → ENDED와 종료 기록, 남은 장소는 TEAM_ENDED.
        confirm(p, 0, "NORMAL", null).andExpect(jsonPath("$.activityStatus").value("RETURNING"));
        confirm(p, 1, "ABORT", "HEALTH").andExpect(jsonPath("$.activityStatus").value("ENDED"));
        assertThat(endRecord(p)).isEqualTo("ALL_RETURNED:LAST_RETURN_CONFIRMED:1:1:0:0:LIVE");
        assertThat(jdbc.queryForList("SELECT visit_status || ':' || not_visited_reason FROM patrol_stops WHERE patrol_id = ?", String.class,
                p.id())).containsOnly("NOT_VISITED:TEAM_ENDED");
        // 종료 뒤에도 같은 요청의 재전송은 최초 결과를 재현하고, 새 요청은 이력만 남긴다.
        api.post(kim, ret(p), request(event, "NORMAL", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.patrolRevision").value(JsonPath.<Integer>read(first, "$.patrolRevision")));
        api.post(park, ret(p), request(UUID.randomUUID(), "NORMAL", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("ENDED")).andExpect(jsonPath("$.returnReason").value("ABORT"));
        assertThat(events(p, "RETURN_REQUESTED")).isEqualTo(4);
        api.post(kim, ret(p), request(event, "ABORT", "DEVICE")).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void oneMemberAbortingKeepsTheOthersActive() throws Exception {
        PatrolFixture.Patrol p = f.patrol(3);
        start(p, 0);
        start(p, 1);
        // 아픈 조원의 개인 중도 복귀: 남은 두 사람은 활동을 계속한다(D-33).
        String body = confirm(p, 0, "ABORT", "HEALTH").andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.returnReason").value("ABORT")).andExpect(jsonPath("$.alreadyConfirmed").value(false))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ReturnConfirmationResult", body);
        assertThat(jdbc.queryForList("SELECT DISTINCT visit_status FROM patrol_stops WHERE patrol_id = ?", String.class, p.id()))
                .containsOnly("PENDING");
        api.post(p.users().get(1), "/api/v1/patrols/" + p.id() + "/stops/" + p.stops().get(0) + "/skip-events",
                "{\"reason\":\"UNSAFE\",\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:20:00Z\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.visitStatus").value("SKIPPED"));
        // 다른 조원은 다른 구분으로 확인해도 충돌하지 않는다.
        confirm(p, 1, "NORMAL", null).andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"));
        confirm(p, 2, "NORMAL", null).andExpect(jsonPath("$.activityStatus").value("ENDED"));
        assertThat(endRecord(p)).isEqualTo("ALL_RETURNED:LAST_RETURN_CONFIRMED:2:1:0:0:LIVE");
        assertThat(jdbc.queryForList("SELECT return_reason || ':' || coalesce(abort_reason, '-') FROM patrol_members WHERE patrol_id = ? ORDER BY seat_no",
                String.class, p.id())).containsExactly("ABORT:HEALTH", "NORMAL:-", "NORMAL:-");
    }

    @Test
    void managerConfirmsWithoutStartRecordAndSameOrDifferentReconfirmation() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        // 시작 기록 없이 직접 복귀 확인(D-24): 활동 흔적이 생겨 IN_PROGRESS, 시작 시각은 생기지 않는다.
        confirm(p, 0, "ABORT", "HEALTH").andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.returned").value(true))
                .andExpect(jsonPath("$.returnSource").value("MANAGER_CONFIRMATION"))
                .andExpect(jsonPath("$.returnConfirmedBy.name").value("최○○"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_members WHERE patrol_id = ? AND started_at IS NOT NULL", Long.class,
                p.id())).isZero();
        // 같은 구분 재확인은 저장 없이 alreadyConfirmed, 다른 구분은 409이며 어느 쪽도 값·version을 바꾸지 않는다.
        long before = version(p);
        confirm(p, 0, "ABORT", "HEALTH").andExpect(status().isOk()).andExpect(jsonPath("$.alreadyConfirmed").value(true))
                .andExpect(jsonPath("$.version").value(before));
        String conflict = confirm(p, 0, "NORMAL", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("RETURN_ALREADY_CONFIRMED"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorActivityStateConflict", conflict);
        assertThat(version(p)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT return_reason FROM patrol_members WHERE participant_id = ?", String.class,
                p.participants().get(0))).isEqualTo("ABORT");
        // 남은 조원은 다른 구분으로 확인해도 된다(조원 간 비교 없음).
        confirm(p, 1, "NORMAL", null).andExpect(jsonPath("$.activityStatus").value("ENDED"));
        assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM patrols WHERE id = ?", Boolean.class, p.id())).isTrue();
    }

    @Test
    void absentMembersAreExcludedButZeroTargetsNeverEnd() throws Exception {
        PatrolFixture.Patrol p = f.patrol(3);
        start(p, 0);
        absent(p, 2);
        confirm(p, 2, "NORMAL", null).andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("MEMBER_ABSENT"));
        confirm(p, 0, "NORMAL", null).andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"));
        confirm(p, 1, "NORMAL", null).andExpect(jsonPath("$.activityStatus").value("ENDED"));
        assertThat(endRecord(p)).isEqualTo("ALL_RETURNED:LAST_RETURN_CONFIRMED:2:0:0:1:LIVE");
        // 결석자 배정 행·출결 이력은 남는다.
        assertThat(jdbc.queryForObject("SELECT attendance FROM patrol_members WHERE participant_id = ?", String.class,
                p.participants().get(2))).isEqualTo("ABSENT");

        // 전원 결석: 나간 사람이 없으므로 끝나지 않고 종료 기록도 없다.
        PatrolFixture.Patrol q = f.patrol(2, PatrolFixture.START.plusSeconds(4 * 3600), PatrolFixture.END.plusSeconds(4 * 3600));
        absent(q, 0);
        absent(q, 1);
        confirm(q, 0, "NORMAL", null).andExpect(jsonPath("$.details.reason").value("MEMBER_ABSENT"));
        assertThat(jdbc.queryForObject("SELECT activity_status FROM patrols WHERE id = ?", String.class, q.id())).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_end_records WHERE patrol_id = ?", Long.class, q.id())).isZero();
    }

    @Test
    void confirmationAfterEndIsStoredWithoutReopeningAndEndRecordStaysTheSame() throws Exception {
        PatrolFixture.Patrol p = f.patrol(3);
        start(p, 0);
        absent(p, 2);
        confirm(p, 0, "NORMAL", null);
        confirm(p, 1, "NORMAL", null).andExpect(jsonPath("$.activityStatus").value("ENDED"));
        String endedBefore = admin(p);
        java.time.Instant finished = jdbc.queryForObject("SELECT finished_at FROM patrols WHERE id = ?", java.time.OffsetDateTime.class,
                p.id()).toInstant();
        // 잘못된 결석을 출석으로 정정(종료 뒤): 조는 다시 열리지 않고 보완 대상이 생긴다(D-37).
        clock.advance(java.time.Duration.ofMinutes(10));
        api.put(f.manager, "/api/v1/admin/patrols/" + p.id() + "/attendance/" + p.participants().get(2),
                "{\"attendance\":\"PRESENT\",\"correctionReason\":\"실제로 함께 활동함\",\"version\":" + version(p) + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.activityStatus").value("ENDED"))
                .andExpect(jsonPath("$.pendingReturnConfirmation").value(true));
        api.get(f.manager, "/api/v1/admin/patrols/" + p.id()).andExpect(jsonPath("$.pendingReturnConfirmations").value(1))
                .andExpect(jsonPath("$.currentOutcomes.inField").value(1));
        // 누락된 복귀 확인 보완: 실제로 저장하고 상태·finished_at·종료 기록은 그대로다.
        confirm(p, 2, "NORMAL", null).andExpect(status().isOk()).andExpect(jsonPath("$.activityStatus").value("ENDED"))
                .andExpect(jsonPath("$.returned").value(true)).andExpect(jsonPath("$.alreadyConfirmed").value(false));
        assertThat(jdbc.queryForObject("SELECT return_reason FROM patrol_members WHERE participant_id = ?", String.class,
                p.participants().get(2))).isEqualTo("NORMAL");
        assertThat(jdbc.queryForObject("SELECT finished_at FROM patrols WHERE id = ?", java.time.OffsetDateTime.class, p.id()).toInstant())
                .isEqualTo(finished);
        assertThat(endRecord(p)).isEqualTo("ALL_RETURNED:LAST_RETURN_CONFIRMED:2:0:0:1:LIVE");
        String after = api.get(f.manager, "/api/v1/admin/patrols/" + p.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingReturnConfirmations").value(0))
                .andExpect(jsonPath("$.currentOutcomes.normalReturned").value(3))
                .andExpect(jsonPath("$.currentOutcomes.absent").value(0))
                .andExpect(jsonPath("$.supplementedAfterEnd.count").value(2))
                .andReturn().getResponse().getContentAsString();
        assertMatches("AdminPatrol", after);
        // 종료 당시 기록은 조회할 때마다 같다.
        assertThat(JsonPath.<Object>read(after, "$.end")).isEqualTo(JsonPath.<Object>read(endedBefore, "$.end"));
        assertThat(JsonPath.<Object>read(admin(p), "$.end")).isEqualTo(JsonPath.<Object>read(after, "$.end"));
    }

    @Test
    void returnTimeBoundariesAndKeepingCorrectedTime() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        start(p, 0);
        // 시작(01:00Z)보다 이른 복귀, 미래, 다른 날은 422.
        confirmAt(p, 0, "2026-10-15T00:59:59Z").andExpect(jsonPath("$.code").value("INVALID_ACTIVITY_TIME"));
        confirmAt(p, 0, "2026-10-15T01:36:00Z").andExpect(jsonPath("$.code").value("INVALID_ACTIVITY_TIME"));
        confirmAt(p, 0, "2026-10-15T01:00:00Z").andExpect(status().isOk());
        // 이미 정정된 복귀 시각은 확인으로 덮어쓰지 않는다.
        jdbc.update("UPDATE patrol_members SET returned_at = ?, return_source = 'ADMIN_CORRECTION' WHERE participant_id = ?",
                PatrolFixture.ts(java.time.Instant.parse("2026-10-15T01:10:00Z")), p.participants().get(1));
        confirmAt(p, 1, "2026-10-15T01:25:00Z").andExpect(jsonPath("$.returnedAt").value("2026-10-15T01:10:00Z"))
                .andExpect(jsonPath("$.returnSource").value("ADMIN_CORRECTION")).andExpect(jsonPath("$.returned").value(true));
    }

    @Test
    void appRequestOverriddenByManagerIsAuditedAndRetriesAreIdempotent() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        start(p, 0);
        api.post(p.users().get(0), ret(p), request(UUID.randomUUID(), "ABORT", "DEVICE")).andExpect(status().isOk());
        UUID key = UUID.randomUUID();
        String first = api.post(f.manager, conf(p), confirmation(p, 0, "2026-10-15T01:20:00Z", "NORMAL", null, version(p)), key)
                .andExpect(jsonPath("$.returnReason").value("NORMAL")).andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action = 'RETURN_CONFIRMED_OVER_REQUEST'",
                Long.class, p.participants().get(0))).isEqualTo(1);
        // 본인 요청값은 그대로 남는다.
        assertThat(jdbc.queryForObject("SELECT requested_return_reason || ':' || requested_abort_reason FROM patrol_members WHERE participant_id = ?",
                String.class, p.participants().get(0))).isEqualTo("ABORT:DEVICE");
        // 같은 키 재시도는 최초 결과, 새 키·같은 구분은 저장 없이 alreadyConfirmed.
        String replay = api.post(f.manager, conf(p), confirmation(p, 0, "2026-10-15T01:20:00Z", "NORMAL", null, JsonPath.<Integer>read(first, "$.version") - 1), key)
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        api.post(f.manager, conf(p), confirmation(p, 0, "2026-10-15T01:25:00Z", "NORMAL", null, version(p)), UUID.randomUUID())
                .andExpect(jsonPath("$.returnedAt").value("2026-10-15T01:20:00Z")).andExpect(jsonPath("$.alreadyConfirmed").value(true));
    }

    @Test
    void concurrentConfirmationsOfDifferentMembersSerializeByVersion() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        long v = version(p);
        List<String> statuses = runConcurrently(List.of(
                () -> String.valueOf(api.post(f.manager, conf(p), confirmation(p, 0, "2026-10-15T01:20:00Z", "NORMAL", null, v), UUID.randomUUID())
                        .andReturn().getResponse().getStatus()),
                () -> String.valueOf(api.post(f.manager, conf(p), confirmation(p, 1, "2026-10-15T01:21:00Z", "NORMAL", null, v), UUID.randomUUID())
                        .andReturn().getResponse().getStatus())));
        assertThat(statuses).containsExactlyInAnyOrder("200", "409");
        // 진 쪽은 최신 version으로 다시 보내 끝낸다. 종료 기록은 한 번만 생긴다.
        long returned = jdbc.queryForObject("SELECT count(*) FROM patrol_members WHERE patrol_id = ? AND return_confirmed_at IS NOT NULL",
                Long.class, p.id());
        assertThat(returned).isEqualTo(1);
        int remaining = jdbc.queryForObject("SELECT seat_no - 1 FROM patrol_members WHERE patrol_id = ? AND return_confirmed_at IS NULL",
                Integer.class, p.id());
        confirm(p, remaining, "NORMAL", null).andExpect(jsonPath("$.activityStatus").value("ENDED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_end_records WHERE patrol_id = ?", Long.class, p.id())).isEqualTo(1);
    }

    private String admin(PatrolFixture.Patrol p) throws Exception {
        return api.get(f.manager, "/api/v1/admin/patrols/" + p.id()).andReturn().getResponse().getContentAsString();
    }

    /** 종료 기록 요약: 사유:계기:정상:중도:이동:결석:출처. */
    private String endRecord(PatrolFixture.Patrol p) {
        return jdbc.queryForObject("""
                SELECT end_reason || ':' || trigger_type || ':' || normal_returned || ':' || abort_returned || ':' || transferred_out || ':'
                    || absent || ':' || record_source FROM patrol_end_records WHERE patrol_id = ?""", String.class, p.id());
    }

    private org.springframework.test.web.servlet.ResultActions confirm(PatrolFixture.Patrol p, int index, String reason, String abort)
            throws Exception {
        return api.post(f.manager, conf(p), confirmation(p, index, "2026-10-15T01:25:00Z", reason, abort, version(p)), UUID.randomUUID());
    }

    private org.springframework.test.web.servlet.ResultActions confirmAt(PatrolFixture.Patrol p, int index, String at) throws Exception {
        return api.post(f.manager, conf(p), confirmation(p, index, at, "NORMAL", null, version(p)), UUID.randomUUID());
    }

    private void start(PatrolFixture.Patrol p, int index) throws Exception {
        api.post(p.users().get(index), "/api/v1/patrols/" + p.id() + "/activity-starts",
                "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:00:00Z\"}").andExpect(status().isOk());
    }

    private void absent(PatrolFixture.Patrol p, int index) throws Exception {
        api.put(f.manager, "/api/v1/admin/patrols/" + p.id() + "/attendance/" + p.participants().get(index),
                "{\"attendance\":\"ABSENT\",\"version\":" + version(p) + "}").andExpect(status().isOk());
    }

    private long version(PatrolFixture.Patrol p) {
        return jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, p.id());
    }

    private long events(PatrolFixture.Patrol p, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ? AND type = ?", Long.class, p.id(), type);
    }

    private String confirmation(PatrolFixture.Patrol p, int index, String at, String reason, String abort, long version) {
        return "{\"participantId\":\"" + p.participants().get(index) + "\",\"returnedAt\":\"" + at + "\",\"returnReason\":\"" + reason + "\""
                + (abort == null ? "" : ",\"abortReason\":\"" + abort + "\"") + ",\"version\":" + version + "}";
    }

    private static String request(UUID id, String reason, String abort) {
        return "{\"returnReason\":\"" + reason + "\"" + (abort == null ? "" : ",\"abortReason\":\"" + abort + "\"") + ",\"clientEventId\":\""
                + id + "\",\"occurredAt\":\"2026-10-15T01:28:00Z\"}";
    }

    private static String ret(PatrolFixture.Patrol p) {
        return "/api/v1/patrols/" + p.id() + "/return-requests";
    }

    private static String conf(PatrolFixture.Patrol p) {
        return "/api/v1/admin/patrols/" + p.id() + "/return-confirmations";
    }

    private List<String> runConcurrently(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
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
}
