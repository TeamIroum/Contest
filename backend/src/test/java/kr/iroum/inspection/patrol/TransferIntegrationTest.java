package kr.iroum.inspection.patrol;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.iroum.inspection.TestcontainersConfiguration;
import kr.iroum.inspection.auth.phone.PhoneCrypto;
import kr.iroum.inspection.auth.session.SessionService;
import kr.iroum.inspection.support.ApiCaller;
import kr.iroum.inspection.support.FixedClockConfiguration;
import kr.iroum.inspection.support.MutableClock;
import kr.iroum.inspection.support.PatrolFixture;

/**
 * 활동 중 조 이동(D-34·D-36): 기록 보존, 빈 조 종료와 전원 결석 구분, 다중 이동 이력과 개인 시각 유지, 정원 3명 동시성, 검증 경계.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class TransferIntegrationTest {

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
    void transferKeepsRecordsCarriesStartAndTheMovedSeniorSeesOnlyTheOldRecord() throws Exception {
        PatrolFixture.Patrol a = f.patrol(2);
        PatrolFixture.Patrol b = f.patrol(2);
        start(a, 0, "2026-10-15T01:02:00Z");
        start(a, 1, "2026-10-15T01:00:00Z");
        start(b, 0, "2026-10-15T01:05:00Z");
        start(b, 1, "2026-10-15T01:06:00Z");
        String candidates = api.get(f.manager, base(a) + "/members/" + a.participants().get(1) + "/transfer-candidates")
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates[0].patrolId").value(b.id().toString()))
                .andExpect(jsonPath("$.candidates[0].inField").value(2)).andExpect(jsonPath("$.candidates[0].full").value(false))
                .andReturn().getResponse().getContentAsString();
        assertMatches("TransferCandidates", candidates);

        String body = transfer(a, 1, b, UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.source.activityStatus").value("IN_PROGRESS")).andExpect(jsonPath("$.source.inField").value(1))
                .andExpect(jsonPath("$.target.inField").value(3))
                .andExpect(jsonPath("$.member.startSource").value("CARRIED"))
                .andExpect(jsonPath("$.member.startedAt").value("2026-10-15T01:00:00Z"))
                .andExpect(jsonPath("$.member.attendance").value("UNCHECKED"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("TransferResult", body);
        // 이전 조 출발 시각은 그대로(이동해 나간 조원의 시작 포함), 새 조 출발 시각은 이어받은 시작으로 앞당기지 않는다.
        assertThat(departed(a)).isEqualTo("2026-10-15T01:00:00Z");
        assertThat(departed(b)).isEqualTo("2026-10-15T01:05:00Z");
        // 이동은 복귀가 아니다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_members WHERE participant_id = ? AND return_confirmed_at IS NOT NULL",
                Long.class, a.participants().get(1))).isZero();
        assertThat(participations(a, 1)).containsExactly("TRANSFERRED:left");
        assertThat(participations(b, a, 1)).containsExactly("OPEN:joined");

        String adminA = api.get(f.manager, base(a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.formerMembers[0].participantId").value(a.participants().get(1).toString()))
                .andExpect(jsonPath("$.formerMembers[0].transferredToPatrolId").value(b.id().toString()))
                .andExpect(jsonPath("$.formerMembers[0].startedAt").value("2026-10-15T01:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("AdminPatrol", adminA);
        String adminB = api.get(f.manager, base(b)).andExpect(jsonPath("$.members[2].transferredFromPatrolId").value(a.id().toString()))
                .andExpect(jsonPath("$.members[2].joinedAt").isString()).andReturn().getResponse().getContentAsString();
        assertMatches("AdminPatrol", adminB);

        // 이동한 시니어: 이전 조는 조회만(myAssignment.active=false), 이동 뒤(01:30 이후) 발생한 기록은 409 MEMBER_TRANSFERRED, 오늘 목록은 새 조만.
        UUID moved = a.users().get(1);
        String old = api.get(moved, "/api/v1/patrols/" + a.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.myAssignment.active").value(false))
                .andExpect(jsonPath("$.myAssignment.transferredToPatrolId").value(b.id().toString()))
                .andReturn().getResponse().getContentAsString();
        assertMatches("Patrol", old);
        assertMatches("SyncState", api.get(moved, "/api/v1/patrols/" + a.id() + "/sync-state").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String rejected = api.post(moved, "/api/v1/patrols/" + a.id() + "/stops/" + a.stops().get(0) + "/skip-events",
                "{\"reason\":\"UNSAFE\",\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:30:00Z\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("MEMBER_TRANSFERRED"))
                .andExpect(jsonPath("$.details.transferredToPatrolId").value(b.id().toString()))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ErrorActivityStateConflict", rejected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ? AND type = 'STOP_SKIPPED'", Long.class, a.id()))
                .isZero();
        api.get(moved, "/api/v1/me/patrols?date=2026-10-15").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(b.id().toString()));
        api.get(moved, "/api/v1/patrols/" + b.id()).andExpect(jsonPath("$.myAssignment.active").value(true));

        // 정원: 현장 조원 3명이면 더 받지 않는다. 이동해 나간 조원을 이전 조에서 복귀 확인하면 409.
        transfer(a, 0, b, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("TARGET_FULL"));
        confirm(a, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("MEMBER_TRANSFERRED"));
        // 남은 조원이 복귀하면 이전 조가 끝난다: 복귀 1 + 이동 1.
        confirm(a, 0).andExpect(jsonPath("$.activityStatus").value("ENDED"));
        assertThat(end(a)).isEqualTo("RETURNED_AND_TRANSFERRED:LAST_RETURN_CONFIRMED:1:0:1:0");
    }

    @Test
    void transferringTheLastFieldMemberEndsTheOldTeamUnlikeAllAbsent() throws Exception {
        PatrolFixture.Patrol a = f.patrol(2);
        PatrolFixture.Patrol c = f.patrol(2);
        PatrolFixture.Patrol d = f.patrol(2);
        for (PatrolFixture.Patrol p : List.of(a, c, d)) {
            start(p, 0, "2026-10-15T01:00:00Z");
            start(p, 1, "2026-10-15T01:00:00Z");
        }
        transfer(a, 0, c, UUID.randomUUID()).andExpect(jsonPath("$.source.activityStatus").value("IN_PROGRESS"));
        transfer(a, 1, d, UUID.randomUUID()).andExpect(jsonPath("$.source.activityStatus").value("ENDED"));
        assertThat(end(a)).isEqualTo("ALL_TRANSFERRED:LAST_MEMBER_TRANSFERRED:0:0:2:0");
        assertThat(jdbc.queryForList("SELECT DISTINCT visit_status || ':' || not_visited_reason FROM patrol_stops WHERE patrol_id = ?",
                String.class, a.id())).containsOnly("NOT_VISITED:TEAM_ENDED");
        // 이전 조의 시작 기록·출발 시각은 남는다.
        assertThat(departed(a)).isEqualTo("2026-10-15T01:00:00Z");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_members WHERE patrol_id = ? AND started_at IS NOT NULL", Long.class, a.id()))
                .isEqualTo(2);
    }

    @Test
    void multipleTransfersKeepEveryIntervalAndThePersonalStart() throws Exception {
        PatrolFixture.Patrol a = f.patrol(2);
        PatrolFixture.Patrol b = f.patrol(2);
        PatrolFixture.Patrol c = f.patrol(2);
        start(a, 0, "2026-10-15T01:03:00Z");
        start(a, 1, "2026-10-15T01:01:00Z");
        start(b, 0, "2026-10-15T01:04:00Z");
        start(c, 0, "2026-10-15T01:05:00Z");
        // A → B → A: 같은 배정 행을 다시 활성화하고 참여 구간을 새로 연다. 원래 시작(SELF)은 그대로다.
        transfer(a, 1, b, UUID.randomUUID()).andExpect(status().isOk());
        clock.advance(java.time.Duration.ofMinutes(1));
        transferBack(b, a.participants().get(1), a).andExpect(status().isOk()).andExpect(jsonPath("$.member.startSource").value("SELF"));
        assertThat(participations(a, 1)).containsExactly("TRANSFERRED:left", "OPEN:joined");
        assertThat(participations(b, a, 1)).containsExactly("TRANSFERRED:left");
        // A → C: C에서는 이어받은 시작.
        clock.advance(java.time.Duration.ofMinutes(1));
        transfer(a, 1, c, UUID.randomUUID()).andExpect(jsonPath("$.member.startSource").value("CARRIED"))
                .andExpect(jsonPath("$.member.startedAt").value("2026-10-15T01:01:00Z"));
        assertThat(jdbc.queryForList("SELECT to_patrol_id FROM patrol_member_transfers WHERE participant_id = ? ORDER BY transferred_at",
                UUID.class, a.participants().get(1))).containsExactly(b.id(), a.id(), c.id());
        assertThat(jdbc.queryForList("SELECT DISTINCT started_at FROM patrol_members WHERE participant_id = ?", OffsetDateTime.class,
                a.participants().get(1)).stream().map(t -> t.toInstant().toString()).toList()).containsExactly("2026-10-15T01:01:00Z");
        assertThat(departed(a)).isEqualTo("2026-10-15T01:01:00Z");
        assertThat(departed(b)).isEqualTo("2026-10-15T01:04:00Z");
        assertThat(departed(c)).isEqualTo("2026-10-15T01:05:00Z");

        // 이어받은 시작을 C에서 정정하면 원래 기록(A)이 정정 출처가 되고 사본들도 같은 시각이 된다.
        api.post(f.manager, base(c) + "/members/" + a.participants().get(1) + "/time-corrections",
                "{\"startedAt\":\"2026-10-15T00:58:00Z\",\"reason\":\"시작 버튼 늦게 누름\",\"version\":" + version(c) + "}", UUID.randomUUID())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.startSource").value("CARRIED"));
        assertThat(jdbc.queryForList("SELECT start_source || '@' || started_at FROM patrol_members WHERE participant_id = ? ORDER BY patrol_id = ?, patrol_id = ?",
                String.class, a.participants().get(1), a.id(), b.id())).allMatch(v -> v.contains("00:58:00"));
        assertThat(jdbc.queryForObject("SELECT start_source FROM patrol_members WHERE patrol_id = ? AND participant_id = ?", String.class, a.id(),
                a.participants().get(1))).isEqualTo("ADMIN_CORRECTION");
        assertThat(departed(a)).isEqualTo("2026-10-15T00:58:00Z");
        assertThat(departed(c)).isEqualTo("2026-10-15T01:05:00Z");
    }

    @Test
    void startCorrectionPropagatedToAnotherTeamBumpsItsVersionAndKeepsItsEndRecord() throws Exception {
        // 리뷰 재현(06ef918): B에서 시작 시각을 정정하면 A의 기록도 바뀌지만 A의 version·revision은 그대로였다.
        PatrolFixture.Patrol a = f.patrol(2);
        PatrolFixture.Patrol b = f.patrol(2);
        start(a, 0, "2026-10-15T01:03:00Z");
        start(a, 1, "2026-10-15T01:01:00Z");
        start(b, 0, "2026-10-15T01:04:00Z");
        transfer(a, 1, b, UUID.randomUUID()).andExpect(status().isOk());
        confirm(a, 0).andExpect(jsonPath("$.activityStatus").value("ENDED"));
        long versionA = version(a);
        long revisionA = jdbc.queryForObject("SELECT revision FROM patrols WHERE id = ?", Long.class, a.id());
        String endBefore = end(a);
        String syncBefore = api.get(a.users().get(0), "/api/v1/patrols/" + a.id() + "/sync-state").andReturn().getResponse().getContentAsString();

        api.post(f.manager, base(b) + "/members/" + a.participants().get(1) + "/time-corrections",
                "{\"startedAt\":\"2026-10-15T00:58:00Z\",\"reason\":\"시작 버튼 늦게 누름\",\"version\":" + version(b) + "}", UUID.randomUUID())
                .andExpect(status().isCreated());

        // 실제 기록이 바뀐 A도 같은 트랜잭션에서 version·revision이 오른다. 종료 기록은 그대로다.
        assertThat(version(a)).isGreaterThan(versionA);
        assertThat(jdbc.queryForObject("SELECT revision FROM patrols WHERE id = ?", Long.class, a.id())).isGreaterThan(revisionA);
        assertThat(end(a)).isEqualTo(endBefore);
        assertThat(departed(a)).isEqualTo("2026-10-15T00:58:00Z");
        String detail = api.get(f.manager, base(a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version(a)))
                .andExpect(jsonPath("$.formerMembers[0].startedAt").value("2026-10-15T00:58:00Z"))
                .andExpect(jsonPath("$.departedAt").value("2026-10-15T00:58:00Z"))
                .andReturn().getResponse().getContentAsString();
        assertMatches("AdminPatrol", detail);
        String sync = api.get(a.users().get(0), "/api/v1/patrols/" + a.id() + "/sync-state").andExpect(status().isOk())
                .andExpect(jsonPath("$.departedAt").value("2026-10-15T00:58:00Z")).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(sync, "$.revision")).isGreaterThan(JsonPath.<Integer>read(syncBefore, "$.revision"));
        // 변경 전 version으로 보낸 관리자 요청은 충돌한다.
        api.put(f.manager, base(a) + "/attendance/" + a.participants().get(0), "{\"attendance\":\"PRESENT\",\"proxyReason\":\"현장 확인\",\"version\":"
                + versionA + "}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void transferValidationsChangeNothing() throws Exception {
        PatrolFixture.Patrol a = f.patrol(3);
        PatrolFixture.Patrol b = f.patrol(3);
        start(a, 0, "2026-10-15T01:00:00Z");
        start(a, 1, "2026-10-15T01:00:00Z");
        PatrolFixture.Patrol assigned = f.patrol(2);
        transfer(a, 0, a, UUID.randomUUID()).andExpect(jsonPath("$.details.reason").value("SAME_PATROL"));
        transfer(assigned, 0, a, UUID.randomUUID()).andExpect(jsonPath("$.details.reason").value("SOURCE_NOT_ACTIVE"));
        transfer(a, 0, assigned, UUID.randomUUID()).andExpect(jsonPath("$.details.reason").value("TARGET_NOT_ACTIVE"));
        // 결석자가 자리를 가진 3인 조(현장 조원 2명)에는 합류할 수 있다(정원은 현장 조원 기준).
        absent(b, 2);
        start(b, 0, "2026-10-15T01:00:00Z");
        String other = f.org("MEETING_POINT", f.agency, "다른 경로당").toString();
        PatrolFixture.Patrol elsewhere = f.patrol(2);
        start(elsewhere, 0, "2026-10-15T01:00:00Z");
        jdbc.update("UPDATE patrols SET base_organization_id = ? WHERE id = ?", UUID.fromString(other), elsewhere.id());
        transfer(a, 0, elsewhere, UUID.randomUUID()).andExpect(jsonPath("$.details.reason").value("TARGET_SCOPE_MISMATCH"));
        absent(a, 2);
        transfer(a, 2, b, UUID.randomUUID()).andExpect(jsonPath("$.details.reason").value("MEMBER_ABSENT"));
        api.post(f.manager, base(a) + "/members/" + a.participants().get(0) + "/transfers",
                body(b, version(a) + 1, version(b), "사유"), UUID.randomUUID())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT")).andExpect(jsonPath("$.details.which").value("source"));
        api.post(f.manager, base(a) + "/members/" + a.participants().get(0) + "/transfers",
                body(b, version(a), version(b) + 1, "사유"), UUID.randomUUID())
                .andExpect(jsonPath("$.details.which").value("target"));
        api.post(f.manager, base(a) + "/members/" + a.participants().get(0) + "/transfers", body(b, version(a), version(b), " "),
                UUID.randomUUID()).andExpect(status().isBadRequest());
        UUID outsider = f.user("타기관");
        PatrolFixture other2 = new PatrolFixture(jdbc, sessions, phones);
        other2.role(outsider, "ACTIVITY_MANAGER", other2.agency);
        api.post(outsider, base(a) + "/members/" + a.participants().get(0) + "/transfers", body(b, version(a), version(b), "사유"),
                UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_member_transfers WHERE from_patrol_id = ?", Long.class, a.id())).isZero();

        // 같은 키 재전송은 최초 결과, 같은 키 다른 내용은 409. 복귀 확인된 조원은 옮길 수 없다.
        UUID key = UUID.randomUUID();
        long va = version(a);
        long vb = version(b);
        String first = api.post(f.manager, base(a) + "/members/" + a.participants().get(0) + "/transfers", body(b, va, vb, "합류"), key)
                .andExpect(status().isOk()).andExpect(jsonPath("$.member.seatNo").value(4)).andReturn().getResponse().getContentAsString();
        assertThat(api.post(f.manager, base(a) + "/members/" + a.participants().get(0) + "/transfers", body(b, va, vb, "합류"), key)
                .andReturn().getResponse().getContentAsString()).isEqualTo(first);
        api.post(f.manager, base(a) + "/members/" + a.participants().get(0) + "/transfers", body(b, va, vb, "다른 사유"), key)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        transfer(a, 0, b, UUID.randomUUID()).andExpect(jsonPath("$.details.reason").value("MEMBER_NOT_ASSIGNED"));
        // 남은 조원이 복귀하면 이전 조는 끝나 더 옮길 수 없다.
        confirm(a, 1).andExpect(jsonPath("$.activityStatus").value("ENDED"));
        transfer(a, 1, b, UUID.randomUUID()).andExpect(jsonPath("$.details.reason").value("SOURCE_NOT_ACTIVE"));
        PatrolFixture.Patrol e = f.patrol(2);
        start(e, 0, "2026-10-15T01:00:00Z");
        confirm(e, 1);
        transfer(e, 1, b, UUID.randomUUID()).andExpect(jsonPath("$.details.reason").value("MEMBER_RETURNED"));
    }

    @Test
    void concurrentJoinsNeverExceedThreeAndOppositeOrSameMemberMovesSerialize() throws Exception {
        PatrolFixture.Patrol target = f.patrol(2);
        PatrolFixture.Patrol a = f.patrol(2);
        PatrolFixture.Patrol c = f.patrol(2);
        for (PatrolFixture.Patrol p : List.of(target, a, c)) {
            start(p, 0, "2026-10-15T01:00:00Z");
            start(p, 1, "2026-10-15T01:00:00Z");
        }
        List<String> codes = run(List.of(() -> code(transfer(a, 0, target, UUID.randomUUID())),
                () -> code(transfer(c, 0, target, UUID.randomUUID()))));
        // 먼저 처리된 이동이 대상 version을 올리므로 뒤 요청은 409(VERSION_CONFLICT 또는 TARGET_FULL)이며 현장 조원은 3명이다.
        assertThat(codes).filteredOn("200"::equals).hasSize(1);
        assertThat(codes).filteredOn(r -> !r.equals("200")).allMatch(r -> r.equals("409:VERSION_CONFLICT") || r.equals("409:ACTIVITY_STATE_CONFLICT:TARGET_FULL"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM patrol_members WHERE patrol_id = ? AND assignment_active AND attendance <> 'ABSENT'
                  AND return_confirmed_at IS NULL""", Long.class, target.id())).isEqualTo(3);

        // 반대 방향 동시 이동은 교착 없이 둘 다 끝난다(id 순서 잠금).
        PatrolFixture.Patrol x = f.patrol(2);
        PatrolFixture.Patrol y = f.patrol(2);
        for (PatrolFixture.Patrol p : List.of(x, y)) {
            start(p, 0, "2026-10-15T01:00:00Z");
            start(p, 1, "2026-10-15T01:00:00Z");
        }
        List<String> opposite = run(List.of(() -> code(transfer(x, 0, y, UUID.randomUUID())), () -> code(transfer(y, 0, x, UUID.randomUUID()))));
        assertThat(opposite).containsExactlyInAnyOrder("200", "409:VERSION_CONFLICT");

        // 같은 조원을 두 조로 동시에: 하나만 성공하고 배정 중 행은 하나다.
        PatrolFixture.Patrol s = f.patrol(2);
        PatrolFixture.Patrol t1 = f.patrol(2);
        PatrolFixture.Patrol t2 = f.patrol(2);
        for (PatrolFixture.Patrol p : List.of(s, t1, t2)) {
            start(p, 0, "2026-10-15T01:00:00Z");
            start(p, 1, "2026-10-15T01:00:00Z");
        }
        assertThat(run(List.of(() -> code(transfer(s, 0, t1, UUID.randomUUID())), () -> code(transfer(s, 0, t2, UUID.randomUUID())))))
                .filteredOn("200"::equals).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_members WHERE participant_id = ? AND assignment_active", Long.class,
                s.participants().get(0))).isEqualTo(1);
    }

    private ResultActions transfer(PatrolFixture.Patrol from, int index, PatrolFixture.Patrol to, UUID key) throws Exception {
        return api.post(f.manager, base(from) + "/members/" + from.participants().get(index) + "/transfers",
                body(to, version(from), version(to), "조원 결원으로 합류"), key);
    }

    private ResultActions transferBack(PatrolFixture.Patrol from, UUID participant, PatrolFixture.Patrol to) throws Exception {
        return api.post(f.manager, base(from) + "/members/" + participant + "/transfers", body(to, version(from), version(to), "원래 조로 복귀 합류"),
                UUID.randomUUID());
    }

    private String body(PatrolFixture.Patrol to, long sourceVersion, long targetVersion, String reason) {
        return "{\"targetPatrolId\":\"" + to.id() + "\",\"sourceVersion\":" + sourceVersion + ",\"targetVersion\":" + targetVersion
                + ",\"reason\":\"" + reason + "\"}";
    }

    private void start(PatrolFixture.Patrol p, int index, String at) throws Exception {
        api.post(p.users().get(index), "/api/v1/patrols/" + p.id() + "/activity-starts",
                "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"" + at + "\"}").andExpect(status().isOk());
    }

    private ResultActions confirm(PatrolFixture.Patrol p, int index) throws Exception {
        return api.post(f.manager, base(p) + "/return-confirmations", "{\"participantId\":\"" + p.participants().get(index)
                + "\",\"returnedAt\":\"2026-10-15T01:25:00Z\",\"returnReason\":\"NORMAL\",\"version\":" + version(p) + "}", UUID.randomUUID());
    }

    private void absent(PatrolFixture.Patrol p, int index) throws Exception {
        api.put(f.manager, base(p) + "/attendance/" + p.participants().get(index), "{\"attendance\":\"ABSENT\",\"version\":" + version(p) + "}")
                .andExpect(status().isOk());
    }

    private long version(PatrolFixture.Patrol p) {
        return jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, p.id());
    }

    private String departed(PatrolFixture.Patrol p) {
        OffsetDateTime at = jdbc.queryForObject("SELECT departed_at FROM patrols WHERE id = ?", OffsetDateTime.class, p.id());
        return at == null ? null : at.toInstant().toString();
    }

    private List<String> participations(PatrolFixture.Patrol p, int index) {
        return participations(p, p, index);
    }

    /** 순회 p에서 (owner의 index번째 참여자)의 참여 구간: 상태:시각 표시. */
    private List<String> participations(PatrolFixture.Patrol p, PatrolFixture.Patrol owner, int index) {
        return jdbc.queryForList("""
                SELECT coalesce(leave_reason, 'OPEN') || ':' || CASE WHEN leave_reason IS NULL THEN (CASE WHEN joined_at IS NULL THEN 'original' ELSE 'joined' END)
                    ELSE (CASE WHEN left_at IS NULL THEN '-' ELSE 'left' END) END
                FROM patrol_participations WHERE patrol_id = ? AND participant_id = ? ORDER BY created_at, (leave_reason IS NULL)""",
                String.class, p.id(), owner.participants().get(index));
    }

    private String end(PatrolFixture.Patrol p) {
        return jdbc.queryForObject("""
                SELECT end_reason || ':' || trigger_type || ':' || normal_returned || ':' || abort_returned || ':' || transferred_out || ':' || absent
                FROM patrol_end_records WHERE patrol_id = ?""", String.class, p.id());
    }

    private String base(PatrolFixture.Patrol p) {
        return "/api/v1/admin/patrols/" + p.id();
    }

    private static String code(ResultActions result) throws Exception {
        var response = result.andReturn().getResponse();
        if (response.getStatus() == 200) {
            return "200";
        }
        String body = response.getContentAsString();
        String code = JsonPath.read(body, "$.code");
        Object reason = "ACTIVITY_STATE_CONFLICT".equals(code) ? JsonPath.read(body, "$.details.reason") : null;
        return response.getStatus() + ":" + code + (reason == null ? "" : ":" + reason);
    }

    private static List<String> run(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        List<String> out = new ArrayList<>();
        for (Future<String> fu : futures) {
            out.add(fu.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return out;
    }

    @SuppressWarnings("unused")
    private static Instant at(String text) {
        return Instant.parse(text);
    }
}
