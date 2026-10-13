package kr.iroum.inspection.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.AuthTestSupport;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.idempotency.IdempotencyService;

/** 03단계 공통 기반: 모든 변경 요청의 CSRF, Idempotency-Key 재현·충돌·동시성. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CommandFoundationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    TransactionTemplate tx;

    record Body(String value, int count) {
    }

    @Test
    void everyBusinessMutationRequiresCsrfEvenWithBearer() throws Exception {
        AuthTestSupport support = new AuthTestSupport(jdbc);
        UUID senior = support.user("김○○", "SENIOR", support.agency);
        long version = jdbc.queryForObject("SELECT auth_version FROM users WHERE id = ?", Long.class, senior);
        String access = sessions.issue(senior, version, "pwa", "휴대폰").accessToken();
        String path = "/api/v1/patrols/" + UUID.randomUUID() + "/attendance-requests";
        mvc.perform(post(path).header("Authorization", "Bearer " + access).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        mvc.perform(csrf.apply(post(path)).header("Authorization", "Bearer " + access).contentType("application/json")
                .content("{}")).andExpect(status().is4xxClientError())
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    void sameKeyReplaysFirstResultAndDifferentBodyConflicts() {
        UUID user = jdbc.queryForObject("INSERT INTO users (name) VALUES ('가상') RETURNING id", UUID.class);
        UUID key = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();
        var first = tx.execute(s -> idempotency.execute(user, "POST /x", key, Map.of("v", 1), Body.class, 201,
                () -> new Body("first", executions.incrementAndGet())));
        var again = tx.execute(s -> idempotency.execute(user, "POST /x", key, Map.of("v", 1), Body.class, 201,
                () -> new Body("second", executions.incrementAndGet())));
        assertThat(first.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(executions).hasValue(1);
        assertThatThrownBy(() -> tx.execute(s -> idempotency.execute(user, "POST /x", key, Map.of("v", 2), Body.class, 201,
                () -> new Body("third", executions.incrementAndGet()))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        // 업무 오류로 롤백된 요청은 기록이 남지 않아 같은 키로 다시 처리할 수 있다.
        UUID failing = UUID.randomUUID();
        assertThatThrownBy(() -> tx.execute(s -> idempotency.execute(user, "POST /x", failing, Map.of("v", 1), Body.class, 200,
                () -> { throw new ApiException(ErrorCode.VERSION_CONFLICT, "충돌"); })));
        var retried = tx.execute(s -> idempotency.execute(user, "POST /x", failing, Map.of("v", 1), Body.class, 200,
                () -> new Body("ok", executions.incrementAndGet())));
        assertThat(retried.replayed()).isFalse();
    }

    @Test
    void concurrentSameKeyExecutesOnce() throws Exception {
        UUID user = jdbc.queryForObject("INSERT INTO users (name) VALUES ('가상') RETURNING id", UUID.class);
        UUID key = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Body>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return tx.execute(s -> idempotency.execute(user, "POST /y", key, Map.of("v", 1), Body.class, 200, () -> {
                    int n = executions.incrementAndGet();
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new Body("once", n);
                })).body();
            }));
        }
        start.countDown();
        List<Body> bodies = new ArrayList<>();
        for (Future<Body> f : results) {
            bodies.add(f.get());
        }
        pool.shutdown();
        assertThat(executions).hasValue(1);
        assertThat(bodies).containsOnly(new Body("once", 1));
    }
}
