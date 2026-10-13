package kr.iroum.inspection.inspection;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.AuthTestSupport;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.review.ReviewTaskOutbox;
import kr.iroum.inspection.support.ApiCaller;
import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.MockAiConfiguration;
import kr.iroum.inspection.support.MockAiServer;
import kr.iroum.inspection.support.MockAiServer.Reply;
import kr.iroum.inspection.support.MutableClock;
import kr.iroum.inspection.support.PatrolFixture;

/**
 * 사진 기능 통합 테스트 공통: 실제 DB + 계약 기반 mock AI(실제 AI 서버·기기 마스킹 검증 아님).
 * 가상 순회: 장소 ①(구도 s0, SIDEWALK) ②(구도 s1·s2, DRAIN) ③(구도 s3, BENCH), 조원 2명, 조원 0이 01:00에 시작, 현재 01:30Z.
 */
// 주기 작업은 끄고 테스트가 직접 부른다(공유 mock AI의 이벤트 순번이 테스트마다 다시 시작하므로).
@SpringBootTest(properties = "inspection.jobs.scheduling-enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, MockAiConfiguration.class})
abstract class PhotoTestBase {
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

    @Autowired
    MockAiServer ai;

    @Autowired
    MockAiConfiguration.TestPaths paths;

    @Autowired
    ReviewTaskOutbox reviews;

    PatrolFixture f;
    PatrolFixture.Patrol p;
    ApiCaller api;
    AuthTestSupport.Csrf csrf;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(FixedClockConfiguration.NOW);
        ai.reset();
        ai.requireKey(MockAiConfiguration.API_KEY);
        f = new PatrolFixture(jdbc, sessions, phones);
        p = f.patrol(2);
        api = new ApiCaller(mvc, f);
        csrf = AuthTestSupport.csrf(mvc);
        api.post(p.users().get(0), "/api/v1/patrols/" + p.id() + "/activity-starts",
                "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:00:00Z\"}").andExpect(status().isOk());
    }

    @AfterEach
    void noMultipartTempFiles() throws Exception {
        // 서버 검사 전 사진은 디스크에 쓰지 않는다(multipart 임시 파일 없음).
        try (Stream<Path> files = Files.walk(paths.multipart())) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    // ---------------------------------------------------------------- 도우미

    protected ResultActions submit(int member, int slot, UUID submission, UUID attempt, String capturedAt, byte[] file) throws Exception {
        return mvc.perform(request(p.users().get(member), p.id(), stopOf(slot), p.slots().get(slot), submission, attempt, capturedAt, file));
    }

    protected MockMultipartHttpServletRequestBuilder request(UUID user, UUID patrol, UUID stop, UUID slot, UUID submission, UUID attempt,
            String capturedAt, byte[] file) {
        MockMultipartHttpServletRequestBuilder b = MockMvcRequestBuilders.multipart("/api/v1/patrols/{p}/stops/{s}/inspections", patrol, stop);
        b.file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", file));
        b.param("clientSubmissionId", submission.toString()).param("captureSlotId", slot.toString()).param("attemptId", attempt.toString())
                .param("capturedAt", capturedAt).param("maskingVersion", "web-mask-0.1.0").param("deviceMasked", "true")
                .param("deviceMaskReport", "{\"faces\":1,\"plates\":0,\"vehicles\":0,\"detector\":\"yunet-web\",\"appVersion\":\"0.3.0\","
                        + "\"boxes\":[{\"kind\":\"face\",\"box\":[0.41,0.12,0.47,0.21]}]}")
                .param("latitude", "37.0002").param("longitude", "127.0002").param("locationAccuracyMeters", "12");
        return withAuth(b, user);
    }

    protected MockMultipartHttpServletRequestBuilder withAuth(MockMultipartHttpServletRequestBuilder b, UUID user) {
        b.cookie(csrf.cookie()).header("X-CSRF-Token", csrf.token()).header("Authorization", f.token(user));
        return b;
    }

    /**
     * 관할 이관 경합: 다른 트랜잭션이 시설 행을 잠근 동안 요청을 보내고, 요청이 그 잠금을 기다리거나(또는 이미 끝나면) 시설 관리 부서를
     * newDepartment로 바꿔 커밋한다. 요청의 HTTP 상태를 돌려준다. waited에는 요청이 시설 잠금을 기다렸는지 담는다.
     */
    protected int duringJurisdictionTransfer(UUID facility, UUID newDepartment, java.util.concurrent.Callable<Integer> request,
            boolean[] waited) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (java.sql.Connection c = jdbc.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (java.sql.PreparedStatement q = c.prepareStatement("SELECT id FROM facilities WHERE id = ? FOR UPDATE")) {
                q.setObject(1, facility);
                q.executeQuery().close();
            }
            Future<Integer> pending = pool.submit(request);
            int waiters = 0;
            for (int i = 0; i < 200 && waiters == 0 && !pending.isDone(); i++) {
                Thread.sleep(25);
                waiters = jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE '%facilities%'""", Integer.class);
            }
            waited[0] = waiters > 0;
            try (java.sql.PreparedStatement q = c.prepareStatement("UPDATE facilities SET managing_department_id = ? WHERE id = ?")) {
                q.setObject(1, newDepartment);
                q.setObject(2, facility);
                q.executeUpdate();
            }
            c.commit();
            return pending.get();
        } finally {
            pool.shutdownNow();
        }
    }

    protected UUID stopOf(int slot) {
        return p.stops().get(slot == 0 ? 0 : slot <= 2 ? 1 : 2);
    }

    protected String stop(int index) {
        return jdbc.queryForObject("SELECT visit_status FROM patrol_stops WHERE id = ?", String.class, p.stops().get(index));
    }

    protected ResultActions skip(int member, int stop, String occurredAt) throws Exception {
        return api.post(p.users().get(member), "/api/v1/patrols/" + p.id() + "/stops/" + p.stops().get(stop) + "/skip-events",
                "{\"reason\":\"UNSAFE\",\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"" + occurredAt + "\"}");
    }

    /** 두 조원 모두 시작·01:15 복귀 확인 → 조 종료(남은 장소 자동 미방문). */
    protected void endPatrol() throws Exception {
        api.post(p.users().get(1), "/api/v1/patrols/" + p.id() + "/activity-starts",
                "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:00:00Z\"}").andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
            long version = jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, p.id());
            api.post(f.manager, "/api/v1/admin/patrols/" + p.id() + "/return-confirmations", "{\"participantId\":\"" + p.participants().get(i)
                    + "\",\"returnedAt\":\"2026-10-15T01:15:00Z\",\"returnReason\":\"NORMAL\",\"version\":" + version + "}", UUID.randomUUID())
                    .andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT activity_status FROM patrols WHERE id = ?", String.class, p.id())).isEqualTo("ENDED");
    }

    /** 이 순회의 검토 작업: 종류:원인:연결 사진 수. */
    protected List<String> tasks() {
        return jdbc.queryForList("""
                SELECT t.type || ':' || t.trigger_reason || ':' || (SELECT count(*) FROM review_task_inspections x WHERE x.review_task_id = t.id)
                FROM review_tasks t JOIN patrol_stops ps ON ps.id = t.patrol_stop_id WHERE ps.patrol_id = ? ORDER BY t.created_at, t.id""",
                String.class, p.id());
    }

    protected void waitForAnalyzeCalls(int n) throws InterruptedException {
        for (int i = 0; i < 200 && ai.analyzeCalls() < n; i++) {
            Thread.sleep(25);
        }
        assertThat(ai.analyzeCalls()).isGreaterThanOrEqualTo(n);
    }
}
