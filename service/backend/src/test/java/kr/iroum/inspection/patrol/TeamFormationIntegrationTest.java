package kr.iroum.inspection.patrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.account.AccountRepository;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.patrol.command.TeamFormationService;
import kr.iroum.inspection.patrol.command.TeamFormationService.UnitKey;
import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.MutableClock;
import kr.iroum.inspection.support.PatrolFixture;

/** 조 편성(D-05)·회차 단위(D-29)·결석 재편성(D-30): 경계, 해산, 출발 후 거절, 겹침, 동시 재편성. */
@SpringBootTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class TeamFormationIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Autowired
    PhoneCrypto phones;

    @Autowired
    MutableClock clock;

    @Autowired
    TeamFormationService formation;

    @Autowired
    AccountRepository accounts;

    PatrolFixture f;
    AuthenticatedUser manager;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockConfiguration.NOW);
        f = new PatrolFixture(jdbc, sessions, phones);
        manager = new AuthenticatedUser(f.manager, UUID.randomUUID(), "최○○", accounts.roles(f.manager));
    }

    @Test
    void unitRulesAtTheBoundaries() {
        // 5명 = 2+3 유효, 같은 회차에 3인 조가 둘이면 위반, 1인 조 위반.
        f.patrol(2);
        f.patrol(3);
        assertThat(formation.violations(unit(PatrolFixture.START))).isEmpty();
        f.patrol(3);
        assertThat(formation.violations(unit(PatrolFixture.START))).anyMatch(v -> v.startsWith("3인 조 2개"));

        // 다른 회차(같은 날 14:00)는 별도 단위: 3인 조 하나가 따로 허용된다(D-29).
        Instant afternoon = Instant.parse("2026-10-15T05:00:00Z");
        f.patrol(3, afternoon, afternoon.plusSeconds(7200));
        f.patrol(2, afternoon, afternoon.plusSeconds(7200));
        assertThat(formation.violations(unit(afternoon))).isEmpty();

        // 짝수 총인원(2+2)에 3인 조는 없어야 하고, 결석을 뺀 실제 인원으로 센다.
        Instant evening = Instant.parse("2026-10-15T08:00:00Z");
        PatrolFixture.Patrol a = f.patrol(2, evening, evening.plusSeconds(7200));
        f.patrol(2, evening, evening.plusSeconds(7200));
        assertThat(formation.violations(unit(evening))).isEmpty();
        jdbc.update("UPDATE patrol_members SET attendance = 'ABSENT', attendance_checked_at = now(), attendance_checked_by = ? WHERE participant_id = ?",
                f.manager, a.participants().get(0));
        assertThat(formation.violations(unit(evening))).anyMatch(v -> v.startsWith("조 인원 1명"));
    }

    @Test
    void absenceIsResolvedByReassignmentBeforeDepartureAndEmptyTeamIsDissolved() {
        PatrolFixture.Patrol a = f.patrol(2);
        PatrolFixture.Patrol b = f.patrol(2);
        jdbc.update("UPDATE patrol_members SET attendance = 'ABSENT', attendance_checked_at = now(), attendance_checked_by = ? WHERE participant_id = ?",
                f.manager, a.participants().get(1));
        // 1명 남은 a의 조원을 b로 옮겨 3인 조, a는 해산.
        reassign(manager, unit(PatrolFixture.START), Map.of(
                a.id(), List.of(),
                b.id(), List.of(b.participants().get(0), b.participants().get(1), a.participants().get(0))), "결석으로 재편성");
        assertThat(formation.violations(unit(PatrolFixture.START))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT activity_status FROM patrols WHERE id = ?", String.class, a.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("SELECT participant_id FROM patrol_members WHERE patrol_id = ? AND assignment_active", UUID.class, b.id()))
                .containsExactlyInAnyOrder(b.participants().get(0), b.participants().get(1), a.participants().get(0));
        // 이전 배정 행·출결 이력은 남는다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_members WHERE patrol_id = ?", Long.class, a.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT attendance FROM patrol_members WHERE participant_id = ? AND patrol_id = ?", String.class,
                a.participants().get(1), a.id())).isEqualTo("ABSENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'TEAM_REASSIGNED' AND resource_id IN (?, ?)", Long.class,
                a.id(), b.id())).isEqualTo(2);
    }

    @Test
    void invalidReassignmentsChangeNothing() {
        PatrolFixture.Patrol a = f.patrol(2);
        PatrolFixture.Patrol b = f.patrol(3);
        // 결과가 규칙 위반(1인 조·3인 조 둘)이면 전부 롤백된다.
        expect(ErrorCode.VALIDATION_FAILED, () -> reassign(manager, unit(PatrolFixture.START),
                Map.of(a.id(), List.of(a.participants().get(0)), b.id(), List.of(b.participants().get(0), b.participants().get(1),
                        b.participants().get(2), a.participants().get(1))), "잘못된 편성"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_members WHERE patrol_id = ? AND assignment_active", Long.class, a.id()))
                .isEqualTo(2);
        // 출발한 조(개인 시작 기록)는 재편성할 수 없다.
        jdbc.update("UPDATE patrol_members SET started_at = ?, start_source = 'SELF' WHERE participant_id = ?",
                PatrolFixture.ts(PatrolFixture.START), a.participants().get(0));
        jdbc.update("UPDATE patrols SET activity_status = 'IN_PROGRESS' WHERE id = ?", a.id());
        expect(ErrorCode.ACTIVITY_ALREADY_RECORDED, () -> reassign(manager, unit(PatrolFixture.START),
                Map.of(a.id(), List.of(a.participants().get(0), a.participants().get(1))), "출발 후"));
        // 같은 참여자를 시간이 겹치는 다른 회차에 넣을 수 없다.
        Instant overlap = PatrolFixture.START.plusSeconds(3600);
        PatrolFixture.Patrol c = f.patrol(2, overlap, overlap.plusSeconds(7200));
        PatrolFixture.Patrol d = f.patrol(3, overlap, overlap.plusSeconds(7200));
        expect(ErrorCode.VALIDATION_FAILED, () -> reassign(manager, unit(overlap),
                Map.of(c.id(), List.of(c.participants().get(0), c.participants().get(1), b.participants().get(0)),
                        d.id(), List.of(d.participants().get(0), d.participants().get(1))), "겹침"));
        // 관할 밖 담당자는 범위를 볼 수 없다.
        UUID other = f.user("다른 담당자");
        f.role(other, "ACTIVITY_MANAGER", f.org("AGENCY", null, "다른 복지관"));
        AuthenticatedUser outsider = new AuthenticatedUser(other, UUID.randomUUID(), "다른 담당자", accounts.roles(other));
        expect(ErrorCode.NOT_FOUND, () -> reassign(outsider, unit(PatrolFixture.START), Map.of(), "범위 밖"));
    }

    @Test
    void concurrentReassignmentsCannotCreateTwoThreePersonTeams() throws Exception {
        // 7명 = A3 + B2 + C2. 한쪽은 A→B, 다른 쪽은 A→C로 각각은 유효하지만 둘 다 반영되면 A가 1명이 된다.
        PatrolFixture.Patrol a = f.patrol(3);
        PatrolFixture.Patrol b = f.patrol(2);
        PatrolFixture.Patrol c = f.patrol(2);
        List<UUID> aa = a.participants();
        List<String> outcomes = runConcurrently(List.of(
                () -> attempt(Map.of(a.id(), List.of(aa.get(0), aa.get(1)), b.id(), List.of(b.participants().get(0), b.participants().get(1), aa.get(2))),
                        "A→B"),
                () -> attempt(Map.of(a.id(), List.of(aa.get(0), aa.get(2)), c.id(), List.of(c.participants().get(0), c.participants().get(1), aa.get(1))),
                        "A→C")));
        assertThat(outcomes).containsOnlyOnce("ok");
        assertThat(formation.violations(unit(PatrolFixture.START))).isEmpty();
    }

    @Test
    void reassignmentRequiresTheLatestVersionOfEveryChangedPatrol() {
        PatrolFixture.Patrol a = f.patrol(2);
        PatrolFixture.Patrol b = f.patrol(3);
        Map<UUID, List<UUID>> swap = Map.of(a.id(), List.of(a.participants().get(0), b.participants().get(0)),
                b.id(), List.of(a.participants().get(1), b.participants().get(1), b.participants().get(2)));
        Map<UUID, Long> seen = versionsOf(swap);
        // 담당자가 본 뒤 다른 변경으로 a의 version이 올랐다면 409 VERSION_CONFLICT, 아무것도 바뀌지 않는다.
        jdbc.update("UPDATE patrols SET version = version + 1 WHERE id = ?", a.id());
        expect(ErrorCode.VERSION_CONFLICT, () -> formation.reassign(manager, unit(PatrolFixture.START), swap, seen, "교체"));
        assertThat(jdbc.queryForList("SELECT participant_id FROM patrol_members WHERE patrol_id = ? AND assignment_active", UUID.class, a.id()))
                .containsExactlyInAnyOrderElementsOf(a.participants());
        // version 누락도 거절한다.
        expect(ErrorCode.VALIDATION_FAILED, () -> formation.reassign(manager, unit(PatrolFixture.START), swap, Map.of(a.id(), 1L), "교체"));
        // 최신 version이면 반영되고 각 순회 version이 오른다.
        long before = jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, b.id());
        formation.reassign(manager, unit(PatrolFixture.START), swap, versionsOf(swap), "교체");
        assertThat(jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, b.id())).isEqualTo(before + 1);
    }

    private void reassign(AuthenticatedUser actor, UnitKey unit, Map<UUID, List<UUID>> desired, String reason) {
        formation.reassign(actor, unit, desired, versionsOf(desired), reason);
    }

    private Map<UUID, Long> versionsOf(Map<UUID, List<UUID>> desired) {
        Map<UUID, Long> versions = new java.util.HashMap<>();
        desired.keySet().forEach(id -> versions.put(id, jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, id)));
        return versions;
    }

    private String attempt(Map<UUID, List<UUID>> desired, String reason) {
        try {
            formation.reassign(manager, unit(PatrolFixture.START), desired, versionsOf(desired), reason);
            return "ok";
        } catch (ApiException e) {
            return e.code().name();
        }
    }

    private UnitKey unit(Instant start) {
        return new UnitKey(f.program, f.agency, f.base, PatrolFixture.DATE, start);
    }

    private static void expect(ErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
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
