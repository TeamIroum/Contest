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
import org.springframework.test.web.servlet.ResultActions;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.support.ApiCaller;
import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.MutableClock;
import kr.iroum.inspection.support.PatrolFixture;

/** 시각 입력·정정(8.3절)과 해산(8.4절): 감사, 경계 시각, 종료 불변, 취소 후 거절, 해산·시작 경합. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class CorrectionCancellationIntegrationTest {

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
    void enteringMissingStartBeginsActivityButCreatesNoConfirmationsAndIsAudited() throws Exception {
        UUID key = UUID.randomUUID();
        String body = correct(0, "{\"startedAt\":\"2026-10-15T01:03:00Z\",\"reason\":\"시작 버튼 누락, 현장 확인\",\"version\":0}", key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctionId").isString())
                .andExpect(jsonPath("$.startSource").value("ADMIN_CORRECTION"))
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.returned").value(false))
                .andReturn().getResponse().getContentAsString();
        assertMatches("TimeCorrectionResult", body);
        // 같은 키 재시도는 같은 201 결과.
        String replay = correct(0, "{\"startedAt\":\"2026-10-15T01:03:00Z\",\"reason\":\"시작 버튼 누락, 현장 확인\",\"version\":0}", key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(body);
        // 복귀만 정정하면 복귀 확인은 생기지 않는다(returned=false). 생략한 시작은 유지된다.
        correct(0, "{\"returnedAt\":\"2026-10-15T01:25:00Z\",\"reason\":\"복귀 시각 기록\",\"version\":1}", UUID.randomUUID())
                .andExpect(jsonPath("$.startedAt").value("2026-10-15T01:03:00Z"))
                .andExpect(jsonPath("$.returnSource").value("ADMIN_CORRECTION"))
                .andExpect(jsonPath("$.returned").value(false))
                .andExpect(jsonPath("$.returnConfirmedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_time_corrections WHERE patrol_id = ?", Long.class, p.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT before_json::text || after_json::text FROM audit_events WHERE resource_id = ? AND action = 'ACTIVITY_TIME_CORRECTED'
                ORDER BY created_at LIMIT 1""", String.class, p.participants().get(0))).contains("\"startedAt\": null", "2026-10-15T01:03:00Z");
        assertThat(jdbc.queryForObject("SELECT departed_at::text FROM patrols WHERE id = ?", String.class, p.id())).startsWith("2026-10-15 01:03");
    }

    @Test
    void correctionValidatesTimesFormatAndMembers() throws Exception {
        correct(0, "{\"startedAt\":\"2026-10-15T01:20:00Z\",\"returnedAt\":\"2026-10-15T01:10:00Z\",\"reason\":\"r\",\"version\":0}",
                UUID.randomUUID()).andExpect(jsonPath("$.code").value("INVALID_ACTIVITY_TIME"));
        correct(0, "{\"startedAt\":\"2026-10-15T01:36:00Z\",\"reason\":\"r\",\"version\":0}", UUID.randomUUID())
                .andExpect(jsonPath("$.code").value("INVALID_ACTIVITY_TIME"));
        correct(0, "{\"startedAt\":\"2026-10-14T14:59:59Z\",\"reason\":\"r\",\"version\":0}", UUID.randomUUID())
                .andExpect(jsonPath("$.code").value("INVALID_ACTIVITY_TIME"));
        correct(0, "{\"startedAt\":null,\"reason\":\"r\",\"version\":0}", UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("startedAt"));
        correct(0, "{\"reason\":\"r\",\"version\":0}", UUID.randomUUID()).andExpect(status().isBadRequest());
        correct(0, "{\"startedAt\":\"2026-10-15T01:00:00Z\",\"reason\":\"   \",\"version\":0}", UUID.randomUUID())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        correct(0, "{\"startedAt\":\"2026-10-15T01:00:00Z\",\"reason\":\"" + "가".repeat(501) + "\",\"version\":0}", UUID.randomUUID())
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("TOO_LONG"));
        correct(0, "{\"startedAt\":\"2026-10-15T01:00:00Z\",\"reason\":\"r\",\"version\":0,\"extra\":1}", UUID.randomUUID())
                .andExpect(status().isBadRequest());
        // 결석 확정 조원의 시각 입력은 MEMBER_ABSENT, 시니어는 403.
        api.put(f.manager, "/api/v1/admin/patrols/" + p.id() + "/attendance/" + p.participants().get(1), "{\"attendance\":\"ABSENT\",\"version\":0}")
                .andExpect(status().isOk());
        correct(1, "{\"startedAt\":\"2026-10-15T01:00:00Z\",\"reason\":\"r\",\"version\":1}", UUID.randomUUID())
                .andExpect(jsonPath("$.details.reason").value("MEMBER_ABSENT"));
        api.post(p.users().get(0), "/api/v1/admin/patrols/" + p.id() + "/members/" + p.participants().get(0) + "/time-corrections",
                "{\"startedAt\":\"2026-10-15T01:00:00Z\",\"reason\":\"r\",\"version\":1}", UUID.randomUUID()).andExpect(status().isForbidden());
    }

    @Test
    void correctionOfFinishedActivityDoesNotReopenIt() throws Exception {
        start(0);
        start(1);
        confirm(0);
        confirm(1);
        long version = version();
        correct(0, "{\"returnedAt\":\"2026-10-15T01:22:00Z\",\"reason\":\"복귀 시각 정정\",\"version\":" + version + "}", UUID.randomUUID())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.activityStatus").value("ENDED"))
                .andExpect(jsonPath("$.returned").value(true));
    }

    @Test
    void cancellationOnlyWithoutActivityAndEverythingAfterwardIsRejected() throws Exception {
        // 출석 요청·출발 확인만 있으면 해산할 수 있고 그 기록은 보존된다.
        api.post(p.users().get(0), "/api/v1/patrols/" + p.id() + "/attendance-requests",
                "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:10:00Z\"}").andExpect(status().isOk());
        api.post(f.manager, "/api/v1/admin/patrols/" + p.id() + "/departure-confirmations", "{\"version\":0}", UUID.randomUUID())
                .andExpect(status().isOk());
        String body = cancel("{\"reason\":\"우천으로 해산\",\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy.name").value("최○○"))
                .andExpect(jsonPath("$.cancellationReason").value("우천으로 해산"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("CancellationResult", body);
        api.get(f.manager, "/api/v1/admin/patrols/" + p.id()).andExpect(jsonPath("$.departureConfirmedAt").isString())
                .andExpect(jsonPath("$.members[0].attendanceRequestedAt").isString())
                .andExpect(jsonPath("$.members[0].startedAt").value(org.hamcrest.Matchers.nullValue()));
        api.get(p.users().get(0), "/api/v1/patrols/" + p.id()).andExpect(jsonPath("$.activityStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.syncAllowed").value(false));
        // 취소 뒤 시작·출석·출발 확인·복귀 확인·시각 정정·재취소는 409 PATROL_CANCELLED.
        api.post(p.users().get(1), "/api/v1/patrols/" + p.id() + "/activity-starts",
                "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:10:00Z\"}")
                .andExpect(jsonPath("$.code").value("PATROL_CANCELLED"));
        api.post(f.manager, "/api/v1/admin/patrols/" + p.id() + "/return-confirmations", "{\"participantId\":\"" + p.participants().get(0)
                + "\",\"returnedAt\":\"2026-10-15T01:20:00Z\",\"returnReason\":\"NORMAL\",\"version\":2}", UUID.randomUUID())
                .andExpect(jsonPath("$.code").value("PATROL_CANCELLED"));
        correct(0, "{\"startedAt\":\"2026-10-15T01:00:00Z\",\"reason\":\"r\",\"version\":2}", UUID.randomUUID())
                .andExpect(jsonPath("$.code").value("PATROL_CANCELLED"));
        cancel("{\"reason\":\"다시\",\"version\":2}").andExpect(jsonPath("$.code").value("PATROL_CANCELLED"));
        cancel("{\"reason\":\" \",\"version\":2}").andExpect(status().isBadRequest());
    }

    @Test
    void activityRecordsBlockCancellation() throws Exception {
        start(0);
        cancel("{\"reason\":\"해산\",\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVITY_ALREADY_RECORDED"));
        // 시작 없이 건너뛰기만 있어도 활동 기록이다.
        PatrolFixture.Patrol q = f.patrol(2, PatrolFixture.START.plusSeconds(4 * 3600), PatrolFixture.END.plusSeconds(4 * 3600));
        api.post(q.users().get(0), "/api/v1/patrols/" + q.id() + "/stops/" + q.stops().get(0) + "/skip-events",
                "{\"reason\":\"UNSAFE\",\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:10:00Z\"}")
                .andExpect(status().isOk());
        api.post(f.manager, "/api/v1/admin/patrols/" + q.id() + "/cancellations", "{\"reason\":\"해산\",\"version\":0}", UUID.randomUUID())
                .andExpect(jsonPath("$.code").value("ACTIVITY_ALREADY_RECORDED"));
    }

    @Test
    void cancellationAndStartRaceHasOneWinner() throws Exception {
        List<String> statuses = runConcurrently(List.of(
                () -> String.valueOf(cancel("{\"reason\":\"해산\",\"version\":0}").andReturn().getResponse().getStatus()),
                () -> String.valueOf(api.post(p.users().get(0), "/api/v1/patrols/" + p.id() + "/activity-starts",
                        "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:10:00Z\"}").andReturn().getResponse()
                        .getStatus())));
        assertThat(statuses).containsOnlyOnce("200").contains("409");
        String state = jdbc.queryForObject("SELECT activity_status FROM patrols WHERE id = ?", String.class, p.id());
        long starts = jdbc.queryForObject("SELECT count(*) FROM patrol_members WHERE patrol_id = ? AND started_at IS NOT NULL", Long.class, p.id());
        assertThat(state.equals("CANCELLED") ? starts == 0 : state.equals("IN_PROGRESS") && starts == 1).isTrue();
    }

    private ResultActions correct(int index, String body, UUID key) throws Exception {
        return api.post(f.manager, "/api/v1/admin/patrols/" + p.id() + "/members/" + p.participants().get(index) + "/time-corrections", body,
                key);
    }

    private ResultActions cancel(String body) throws Exception {
        return api.post(f.manager, "/api/v1/admin/patrols/" + p.id() + "/cancellations", body, UUID.randomUUID());
    }

    private void start(int index) throws Exception {
        api.post(p.users().get(index), "/api/v1/patrols/" + p.id() + "/activity-starts",
                "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:00:00Z\"}").andExpect(status().isOk());
    }

    private void confirm(int index) throws Exception {
        api.post(f.manager, "/api/v1/admin/patrols/" + p.id() + "/return-confirmations", "{\"participantId\":\"" + p.participants().get(index)
                + "\",\"returnedAt\":\"2026-10-15T01:20:00Z\",\"returnReason\":\"NORMAL\",\"version\":" + version() + "}", UUID.randomUUID())
                .andExpect(status().isOk());
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, p.id());
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
