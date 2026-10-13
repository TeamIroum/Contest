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

/** 장소 건너뛰기·쉬기(업무 API 6.5절): 다음 안내, 늦은 이벤트, 멱등, 동시 건너뛰기. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class StopEventIntegrationTest {

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
        api.post(p.users().get(0), "/api/v1/patrols/" + p.id() + "/activity-starts",
                "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:00:00Z\"}").andExpect(status().isOk());
    }

    @Test
    void skipMovesToNextStopThenReturnToCenterWithoutTouchingFacilityLedger() throws Exception {
        String typeBefore = jdbc.queryForObject("SELECT type || status FROM facilities WHERE id = ?", String.class, f.facilities.get(0));
        UUID event = UUID.randomUUID();
        String body = api.post(p.users().get(0), skip(0), skipBody(event, "FACILITY_REMOVED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.visitStatus").value("SKIPPED"))
                .andExpect(jsonPath("$.nextAction").value("NEXT_STOP"))
                .andExpect(jsonPath("$.nextStopId").value(p.stops().get(1).toString()))
                .andExpect(jsonPath("$.nextCaptureSlotId").value(p.slots().get(1).toString()))
                .andReturn().getResponse().getContentAsString();
        assertMatches("SkipResult", body);
        assertThat(jdbc.queryForObject("SELECT type || status FROM facilities WHERE id = ?", String.class, f.facilities.get(0)))
                .isEqualTo(typeBefore);
        assertThat(jdbc.queryForObject("SELECT reason_code FROM patrol_events WHERE client_event_id = ?", String.class, event))
                .isEqualTo("FACILITY_REMOVED");
        assertThat(jdbc.queryForObject("SELECT resolution FROM capture_slots WHERE id = ?", String.class, p.slots().get(0)))
                .isEqualTo("SKIPPED");
        // 같은 이벤트 재전송은 최초 결과, 같은 장소에 대한 새 건너뛰기는 이력만.
        api.post(p.users().get(0), skip(0), skipBody(event, "FACILITY_REMOVED")).andExpect(jsonPath("$.duplicate").value(true));
        long revision = jdbc.queryForObject("SELECT revision FROM patrols WHERE id = ?", Long.class, p.id());
        api.post(p.users().get(1), skip(0), skipBody(UUID.randomUUID(), "UNSAFE")).andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.patrolRevision").value(revision));
        api.post(p.users().get(0), skip(1), skipBody(UUID.randomUUID(), "NOT_FOUND")).andExpect(jsonPath("$.nextStopId")
                .value(p.stops().get(2).toString()));
        api.post(p.users().get(0), skip(2), skipBody(UUID.randomUUID(), "OTHER")).andExpect(jsonPath("$.nextAction").value("RETURN_TO_CENTER"))
                .andExpect(jsonPath("$.nextStopId").value(org.hamcrest.Matchers.nullValue()));
        api.get(p.users().get(0), "/api/v1/patrols/" + p.id()).andExpect(jsonPath("$.progress.skipped").value(3))
                .andExpect(jsonPath("$.currentStopId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void skipValidatesStopOwnershipAndLateEventsDoNotChangeFinishedActivity() throws Exception {
        PatrolFixture.Patrol other = f.patrol(2, PatrolFixture.START.plusSeconds(4 * 3600), PatrolFixture.END.plusSeconds(4 * 3600));
        api.post(p.users().get(0), "/api/v1/patrols/" + p.id() + "/stops/" + other.stops().get(0) + "/skip-events",
                skipBody(UUID.randomUUID(), "UNSAFE")).andExpect(status().isNotFound());
        api.post(p.users().get(0), skip(0), "{\"reason\":\"BORED\",\"clientEventId\":\"" + UUID.randomUUID()
                + "\",\"occurredAt\":\"2026-10-15T01:10:00Z\"}").andExpect(status().isBadRequest());
        // 조가 끝나 자동 미방문(TEAM_ENDED)이 된 장소에 늦게 온 건너뛰기는 이력만 남는다(늦은 사진 보완을 막지 않음, 설계안 5.5).
        for (int i = 0; i < 2; i++) {
            long version = jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, p.id());
            api.post(f.manager, "/api/v1/admin/patrols/" + p.id() + "/return-confirmations", "{\"participantId\":\"" + p.participants().get(i)
                    + "\",\"returnedAt\":\"2026-10-15T01:25:00Z\",\"returnReason\":\"NORMAL\",\"version\":" + version + "}", UUID.randomUUID())
                    .andExpect(status().isOk());
        }
        api.post(p.users().get(1), skip(0), skipBody(UUID.randomUUID(), "UNSAFE")).andExpect(jsonPath("$.visitStatus").value("NOT_VISITED"))
                .andExpect(jsonPath("$.nextAction").value("RETURN_TO_CENTER"));
        assertThat(jdbc.queryForObject("SELECT not_visited_reason FROM patrol_stops WHERE id = ?", String.class, p.stops().get(0)))
                .isEqualTo("TEAM_ENDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ? AND type = 'STOP_SKIPPED'", Long.class, p.id()))
                .isEqualTo(1);
    }

    @Test
    void pauseIsAPersonalRecordOnlyDuringActivity() throws Exception {
        String started = api.post(p.users().get(0), pause(), pauseBody(UUID.randomUUID(), "PAUSE_STARTED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true)).andReturn().getResponse().getContentAsString();
        assertMatches("PauseResult", started);
        // 쉬기는 개인 상태다(D-38): 다른 조원의 쉬기 시작·종료는 김○○의 값을 바꾸지 않는다.
        api.post(p.users().get(1), pause(), pauseBody(UUID.randomUUID(), "PAUSE_STARTED")).andExpect(jsonPath("$.paused").value(true));
        api.post(p.users().get(1), pause(), pauseBody(UUID.randomUUID(), "PAUSE_ENDED")).andExpect(jsonPath("$.paused").value(false));
        assertThat(jdbc.queryForList("SELECT paused FROM patrol_members WHERE patrol_id = ? ORDER BY seat_no", Boolean.class, p.id()))
                .containsExactly(true, false);
        // 시작 전 순회의 쉬기는 상태를 바꾸지 않는다.
        PatrolFixture.Patrol q = f.patrol(2, PatrolFixture.START.plusSeconds(4 * 3600), PatrolFixture.END.plusSeconds(4 * 3600));
        api.post(q.users().get(0), "/api/v1/patrols/" + q.id() + "/pause-events", pauseBody(UUID.randomUUID(), "PAUSE_STARTED"))
                .andExpect(jsonPath("$.paused").value(false));
    }

    @Test
    void concurrentSkipsOfSameStopChangeItOnce() throws Exception {
        List<String> results = runConcurrently(List.of(
                () -> api.post(p.users().get(0), skip(0), skipBody(UUID.randomUUID(), "UNSAFE")).andReturn().getResponse().getContentAsString(),
                () -> api.post(p.users().get(1), skip(0), skipBody(UUID.randomUUID(), "NOT_FOUND")).andReturn().getResponse().getContentAsString()));
        assertThat(results).allSatisfy(r -> assertThat((String) JsonPath.read(r, "$.visitStatus")).isEqualTo("SKIPPED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ? AND type = 'STOP_SKIPPED'", Long.class, p.id()))
                .isEqualTo(2);
        assertThat(results.stream().map(r -> ((Number) JsonPath.read(r, "$.patrolRevision")).longValue()).distinct().count()).isEqualTo(1);
    }

    @Test
    void skipAfterOwnConfirmedReturnIsRejectedButEarlierLateSkipIsAccepted() throws Exception {
        // 리뷰 재현(06ef918): 3인 조에서 복귀 확인된 조원이 복귀 뒤 시각으로 보낸 건너뛰기가 200으로 장소를 SKIPPED로 바꿨다.
        PatrolFixture.Patrol q = f.patrol(3, PatrolFixture.START.plusSeconds(4 * 3600), PatrolFixture.END.plusSeconds(4 * 3600));
        jdbc.update("UPDATE patrols SET scheduled_start_at = ?, expected_return_at = ? WHERE id = ?", PatrolFixture.ts(PatrolFixture.START),
                PatrolFixture.ts(PatrolFixture.END), q.id());
        for (int i = 0; i < 3; i++) {
            api.post(q.users().get(i), "/api/v1/patrols/" + q.id() + "/activity-starts",
                    "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:00:00Z\"}").andExpect(status().isOk());
        }
        long version = jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, q.id());
        api.post(f.manager, "/api/v1/admin/patrols/" + q.id() + "/return-confirmations", "{\"participantId\":\"" + q.participants().get(2)
                + "\",\"returnedAt\":\"2026-10-15T01:20:00Z\",\"returnReason\":\"ABORT\",\"abortReason\":\"HEALTH\",\"version\":" + version + "}",
                UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"));
        UUID returned = q.users().get(2);
        // 복귀(01:20) 뒤 발생한 새 건너뛰기는 거절하고 장소·이벤트를 남기지 않는다.
        String rejected = api.post(returned, "/api/v1/patrols/" + q.id() + "/stops/" + q.stops().get(0) + "/skip-events",
                        "{\"reason\":\"UNSAFE\",\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:25:00Z\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("MEMBER_RETURNED"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorActivityStateConflict", rejected);
        assertThat(jdbc.queryForObject("SELECT visit_status FROM patrol_stops WHERE id = ?", String.class, q.stops().get(0))).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ? AND type = 'STOP_SKIPPED'", Long.class, q.id()))
                .isZero();
        // 복귀 전(01:15)에 발생해 늦게 도착한 건너뛰기는 기존 규칙대로 접수한다(D-38·업무 API 7절).
        api.post(returned, "/api/v1/patrols/" + q.id() + "/stops/" + q.stops().get(1) + "/skip-events",
                        "{\"reason\":\"UNSAFE\",\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:15:00Z\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.visitStatus").value("SKIPPED")).andExpect(jsonPath("$.stopUpdated").value(true));
    }

    private String skip(int stop) {
        return "/api/v1/patrols/" + p.id() + "/stops/" + p.stops().get(stop) + "/skip-events";
    }

    private String pause() {
        return "/api/v1/patrols/" + p.id() + "/pause-events";
    }

    private static String skipBody(UUID id, String reason) {
        return "{\"reason\":\"" + reason + "\",\"clientEventId\":\"" + id + "\",\"occurredAt\":\"2026-10-15T01:10:00Z\"}";
    }

    private static String pauseBody(UUID id, String type) {
        return "{\"type\":\"" + type + "\",\"clientEventId\":\"" + id + "\",\"occurredAt\":\"2026-10-15T01:15:00Z\"}";
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
