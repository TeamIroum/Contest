package kr.iroum.inspection.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import kr.iroum.inspection.TestcontainersConfiguration;

/**
 * 인증 리뷰 3번 회귀: 같은 계정의 서로 다른 제한 세션으로 동시에 비밀번호를 바꾸면 하나만 성공하고
 * 나머지는 무효화된 인증(401)으로 거부돼야 한다. DB 교착(40P01)으로 500이 나면 안 된다.
 * 교착은 타이밍에 따라 나므로 여러 계정으로 여러 번 반복한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConcurrentPasswordChangeRegressionTest {

    static final String TEMPORARY = "Temp-only-pw2";
    static final int ROUNDS = 8;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Test
    void differentRestrictedSessionsChangingConcurrentlySucceedOnceWithoutDeadlock() throws Exception {
        AuthTestSupport support = new AuthTestSupport(jdbc);
        AuthTestSupport.Csrf csrf = AuthTestSupport.csrf(mvc);
        List<List<Integer>> rounds = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            String loginId = support.admin(encoder, TEMPORARY, true);
            // ① 같은 계정으로 제한 세션 A·B(그리고 C)를 따로 발급
            List<Cookie> restricted = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                restricted.add(mvc.perform(csrf.apply(post("/api/v1/auth/admin/sessions")).contentType("application/json")
                        .content("""
                                {"loginId":"%s","password":"%s","clientId":"admin-web","deviceLabel":"PC %d"}""".formatted(loginId, TEMPORARY, i)))
                        .andExpect(status().isOk()).andReturn().getResponse().getCookie("__Host-password-change"));
            }
            // ② 서로 다른 제한 세션으로 동시에 변경(같은 세션 중복 요청도 하나 섞는다)
            List<Cookie> requests = List.of(restricted.get(0), restricted.get(1), restricted.get(2), restricted.get(0));
            ExecutorService pool = Executors.newFixedThreadPool(requests.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                Cookie cookie = requests.get(i);
                String next = "Race-only-pw" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(csrf.apply(post("/api/v1/auth/password-changes")).cookie(cookie).contentType("application/json")
                            .content("{\"currentPassword\":\"" + TEMPORARY + "\",\"newPassword\":\"" + next + "\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            pool.shutdown();
            rounds.add(statuses);
        }
        assertThat(rounds).allSatisfy(statuses -> {
            assertThat(statuses).as("교착·서버 오류 없이 204 하나와 401만").containsOnlyOnce(204);
            assertThat(statuses).filteredOn(s -> s != 204).containsOnly(401);
        });
    }
}
