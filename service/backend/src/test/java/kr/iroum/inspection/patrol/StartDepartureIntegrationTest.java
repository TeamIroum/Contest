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

/** 개인 시작·담당자 출발 확인(D-02)의 독립성, 1인 출발 방지(D-30), 재호출(D-31), 동시 요청. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class StartDepartureIntegrationTest {

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
    void firstStartBeginsActivityWithoutTouchingOthersOrDepartureConfirmation() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        String body = api.post(p.users().get(0), start(p), event(UUID.randomUUID(), "2026-10-15T01:05:00Z")).andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-15T01:05:00Z"))
                .andExpect(jsonPath("$.startReceivedAt").value("2026-10-15T01:30:00Z"))
                .andExpect(jsonPath("$.startSource").value("SELF"))
                .andExpect(jsonPath("$.departedAt").value("2026-10-15T01:05:00Z"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ActivityStartResult", body);
        // 다른 조원의 시각·담당자 출발 확인은 생기지 않는다.
        api.get(f.manager, "/api/v1/admin/patrols/" + p.id()).andExpect(jsonPath("$.members[1].startedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.departureConfirmedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.members[1].startMissing").value(true));
        // 다시 눌러도 최초 시각, 늦게 온 더 이른 시작은 departedAt만 다시 계산한다.
        api.post(p.users().get(0), start(p), event(UUID.randomUUID(), "2026-10-15T01:10:00Z"))
                .andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.startedAt").value("2026-10-15T01:05:00Z"));
        api.post(p.users().get(1), start(p), event(UUID.randomUUID(), "2026-10-15T01:02:00Z"))
                .andExpect(jsonPath("$.departedAt").value("2026-10-15T01:02:00Z"))
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"));
    }

    @Test
    void departureConfirmationIsIndependentAndKeepsFirstValueOnRecall() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        UUID key = UUID.randomUUID();
        String first = api.post(f.manager, departure(p), "{\"version\":0}", key).andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.departureConfirmedAt").value("2026-10-15T01:30:00Z"))
                .andExpect(jsonPath("$.departureConfirmedBy.name").value("최○○"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        assertMatches("DepartureResult", first);
        // 같은 키는 최초 결과 재현, 같은 키 다른 본문은 409.
        clock.advance(java.time.Duration.ofMinutes(3));
        String replay = api.post(f.manager, departure(p), "{\"version\":0}", key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        api.post(f.manager, departure(p), "{\"version\":1}", key).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // D-31: 다른 담당자가 새 키로 다시 확인해도 최초 확인자·시각 유지, version 증가 없음.
        UUID second = f.user("다른 활동 담당자");
        f.role(second, "ACTIVITY_MANAGER", f.agency);
        api.post(second, departure(p), "{\"version\":1}", UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.departureConfirmedAt").value("2026-10-15T01:30:00Z"))
                .andExpect(jsonPath("$.departureConfirmedBy.name").value("최○○"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action = 'DEPARTURE_RECONFIRM_IGNORED'",
                Long.class, p.id())).isEqualTo(1);
        // 확인 뒤 개인 시작은 그대로 동작한다(서로 기다리지 않음).
        api.post(p.users().get(1), start(p), event(UUID.randomUUID(), "2026-10-15T01:31:00Z"))
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"));
        api.post(f.manager, departure(p), "{\"version\":0}", UUID.randomUUID()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        api.post(f.manager, departure(p), "{\"version\":1}").andExpect(status().isBadRequest());
    }

    @Test
    void soloDepartureIsRejectedForBothPathsAtTheBoundary() throws Exception {
        // 2인 조에서 1명 결석 → 참여 가능 1명: 개인 시작·출발 확인 모두 거절.
        PatrolFixture.Patrol two = f.patrol(2);
        absent(two, 1, 0);
        String solo = api.post(two.users().get(0), start(two), event(UUID.randomUUID(), "2026-10-15T01:05:00Z"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("SOLO_DEPARTURE"))
                .andExpect(jsonPath("$.details.activityStatus").value("ASSIGNED"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorActivityStateConflict", solo);
        api.post(f.manager, departure(two), "{\"version\":1}", UUID.randomUUID())
                .andExpect(jsonPath("$.details.reason").value("SOLO_DEPARTURE"));
        // 결석자 본인의 시작은 MEMBER_ABSENT. 거절된 시작은 이력도 남기지 않는다.
        api.post(two.users().get(1), start(two), event(UUID.randomUUID(), "2026-10-15T01:05:00Z"))
                .andExpect(jsonPath("$.details.reason").value("MEMBER_ABSENT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ?", Long.class, two.id())).isZero();
        // 출석 미확인(UNCHECKED)은 결석이 아니므로 참여 가능 인원에 들어간다.
        PatrolFixture.Patrol unchecked = f.patrol(2, PatrolFixture.START.plusSeconds(3 * 3600), PatrolFixture.END.plusSeconds(3 * 3600));
        api.post(unchecked.users().get(0), start(unchecked), event(UUID.randomUUID(), "2026-10-15T01:05:00Z")).andExpect(status().isOk());
        // 3인 조에서 1명 결석 → 참여 가능 2명: 허용.
        PatrolFixture.Patrol three = f.patrol(3, PatrolFixture.START.plusSeconds(6 * 3600), PatrolFixture.END.plusSeconds(6 * 3600));
        absent(three, 2, 0);
        api.post(f.manager, departure(three), "{\"version\":1}", UUID.randomUUID()).andExpect(status().isOk());
        api.post(three.users().get(0), start(three), event(UUID.randomUUID(), "2026-10-15T01:05:00Z")).andExpect(status().isOk());
        // 3인 조에서 2명 결석 → 참여 가능 1명: 시작·출발 확인 모두 거절.
        PatrolFixture.Patrol threeTwoAbsent = f.patrol(3, PatrolFixture.START.plusSeconds(9 * 3600), PatrolFixture.END.plusSeconds(9 * 3600));
        absent(threeTwoAbsent, 1, 0);
        absent(threeTwoAbsent, 2, 1);
        api.post(threeTwoAbsent.users().get(0), start(threeTwoAbsent), event(UUID.randomUUID(), "2026-10-15T01:06:00Z"))
                .andExpect(jsonPath("$.details.reason").value("SOLO_DEPARTURE"));
        api.post(f.manager, departure(threeTwoAbsent), "{\"version\":2}", UUID.randomUUID())
                .andExpect(jsonPath("$.details.reason").value("SOLO_DEPARTURE"));
        // 출발 뒤 결석 확정(출발 후 결원)으로 1명만 남아도 이미 시작한 조원의 재클릭은 중복으로 처리한다.
        absent(three, 1, 2);
        api.post(three.users().get(0), start(three), event(UUID.randomUUID(), "2026-10-15T01:06:00Z"))
                .andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    void cancelledPatrolRejectsStartAndDeparture() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        jdbc.update("""
                UPDATE patrols SET activity_status = 'CANCELLED', cancelled_at = now(), cancelled_by = ?, cancellation_reason = '우천'
                WHERE id = ?""", f.manager, p.id());
        api.post(p.users().get(0), start(p), event(UUID.randomUUID(), "2026-10-15T01:05:00Z"))
                .andExpect(jsonPath("$.code").value("PATROL_CANCELLED"));
        api.post(f.manager, departure(p), "{\"version\":0}", UUID.randomUUID()).andExpect(jsonPath("$.code").value("PATROL_CANCELLED"));
    }

    @Test
    void concurrentStartsAndAbsenceRaceStayConsistent() throws Exception {
        PatrolFixture.Patrol p = f.patrol(3);
        List<String> starts = runConcurrently(List.of(
                () -> api.post(p.users().get(0), start(p), event(UUID.randomUUID(), "2026-10-15T01:05:00Z")).andReturn().getResponse()
                        .getContentAsString(),
                () -> api.post(p.users().get(1), start(p), event(UUID.randomUUID(), "2026-10-15T01:04:00Z")).andReturn().getResponse()
                        .getContentAsString()));
        assertThat(starts).allSatisfy(s -> assertThat((String) JsonPath.read(s, "$.activityStatus")).isEqualTo("IN_PROGRESS"));
        assertThat(jdbc.queryForObject("SELECT departed_at::text FROM patrols WHERE id = ?", String.class, p.id())).startsWith("2026-10-15 01:04");

        // 같은 조원의 시작과 결석 처리가 겹치면 한쪽만 반영된다.
        PatrolFixture.Patrol q = f.patrol(3, PatrolFixture.START.plusSeconds(3 * 3600), PatrolFixture.END.plusSeconds(3 * 3600));
        List<String> race = runConcurrently(List.of(
                () -> String.valueOf(api.post(q.users().get(2), start(q), event(UUID.randomUUID(), "2026-10-15T01:05:00Z")).andReturn()
                        .getResponse().getStatus()),
                () -> String.valueOf(api.put(f.manager, "/api/v1/admin/patrols/" + q.id() + "/attendance/" + q.participants().get(2),
                        "{\"attendance\":\"ABSENT\",\"version\":0}").andReturn().getResponse().getStatus())));
        assertThat(race).containsOnlyOnce("200").contains("409");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM patrol_members WHERE patrol_id = ? AND attendance = 'ABSENT' AND started_at IS NOT NULL""",
                Long.class, q.id())).isZero();

        // 서로 다른 키로 동시에 출발 확인해도 확인자·시각은 하나다.
        PatrolFixture.Patrol r = f.patrol(2, PatrolFixture.START.plusSeconds(6 * 3600), PatrolFixture.END.plusSeconds(6 * 3600));
        List<String> confirms = runConcurrently(List.of(
                () -> api.post(f.manager, departure(r), "{\"version\":0}", UUID.randomUUID()).andReturn().getResponse().getContentAsString(),
                () -> api.post(f.manager, departure(r), "{\"version\":0}", UUID.randomUUID()).andReturn().getResponse().getContentAsString()));
        assertThat(confirms).filteredOn(s -> s.contains("departureConfirmedAt")).hasSizeGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, r.id())).isEqualTo(1);
    }

    private void absent(PatrolFixture.Patrol p, int index, long version) throws Exception {
        api.put(f.manager, "/api/v1/admin/patrols/" + p.id() + "/attendance/" + p.participants().get(index),
                "{\"attendance\":\"ABSENT\",\"version\":" + version + "}").andExpect(status().isOk());
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

    private static String start(PatrolFixture.Patrol p) {
        return "/api/v1/patrols/" + p.id() + "/activity-starts";
    }

    private static String departure(PatrolFixture.Patrol p) {
        return "/api/v1/admin/patrols/" + p.id() + "/departure-confirmations";
    }

    private static String event(UUID id, String occurredAt) {
        return "{\"clientEventId\":\"" + id + "\",\"occurredAt\":\"" + occurredAt + "\"}";
    }
}
