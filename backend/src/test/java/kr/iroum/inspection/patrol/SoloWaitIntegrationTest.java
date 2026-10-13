package kr.iroum.inspection.patrol;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hamcrest.Matchers;
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

/**
 * 혼자 남은 대기(D-35)와 대기 전후 건너뛰기·쉬기(D-38), 이동 전 이벤트의 늦은 접수(설계안 4.7), assignmentRef 재전송 규칙(4.8),
 * 사진 처리 중 건너뛰기 보류(5.3). 판정은 발생 시각 기준이며 대기 구간은 서버 기록 시각에 열린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class SoloWaitIntegrationTest {

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
    void waitStartsWhenAloneAndSkipsAreJudgedByOccurrenceTime() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        start(p, 0);
        start(p, 1);
        UUID kim = p.users().get(0);
        // 박○○ 개인 중도 복귀 확인(01:30): 김○○ 혼자 남아 대기 시작(서버 시각).
        confirm(p, 1, "ABORT").andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"));
        api.get(f.manager, "/api/v1/admin/patrols/" + p.id()).andExpect(jsonPath("$.soloWaiting.participantId").value(p.participants().get(0).toString()))
                .andExpect(jsonPath("$.soloWaiting.since").value("2026-10-15T01:30:00Z"));
        clock.advance(Duration.ofMinutes(5));
        // 대기 중(01:32)에 발생한 건너뛰기는 거절, 이벤트도 남지 않는다.
        UUID during = UUID.randomUUID();
        String waiting = skip(kim, p, 0, during, "2026-10-15T01:32:00Z").andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("SOLO_WAITING")).andReturn().getResponse().getContentAsString();
        assertMatches("ErrorActivityStateConflict", waiting);
        assertThat(events(p, "STOP_SKIPPED")).isZero();
        skip(kim, p, 0, during, "2026-10-15T01:32:00Z").andExpect(jsonPath("$.details.reason").value("SOLO_WAITING"));
        // 대기 전(01:20)에 발생한 건너뛰기가 지금 도착: 접수하고 장소를 바꾸며 다음 안내는 대기.
        String late = skip(kim, p, 0, UUID.randomUUID(), "2026-10-15T01:20:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.visitStatus").value("SKIPPED")).andExpect(jsonPath("$.stopUpdated").value(true))
                .andExpect(jsonPath("$.nextAction").value("WAIT_FOR_INSTRUCTION")).andReturn().getResponse().getContentAsString();
        assertMatches("SkipResult", late);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM capture_slots WHERE patrol_stop_id = ? AND skipped_by_event_id IS NOT NULL",
                Long.class, p.stops().get(0))).isEqualTo(1);
        // 대기 중 쉬기 시작·종료는 개인 기록으로 허용되고, 쉬기 종료로 대기가 풀리지 않는다(D-38).
        pause(kim, p, "PAUSE_STARTED", "2026-10-15T01:33:00Z").andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(true));
        pause(kim, p, "PAUSE_ENDED", "2026-10-15T01:34:00Z").andExpect(jsonPath("$.paused").value(false));
        skip(kim, p, 1, UUID.randomUUID(), "2026-10-15T01:34:30Z").andExpect(jsonPath("$.details.reason").value("SOLO_WAITING"));
        String sync = api.get(kim, "/api/v1/patrols/" + p.id() + "/sync-state").andExpect(status().isOk())
                .andExpect(jsonPath("$.soloWaiting.participantId").value(p.participants().get(0).toString()))
                .andReturn().getResponse().getContentAsString();
        assertMatches("SyncState", sync);
        // 복귀 요청은 허용되고 대기를 닫는다. 닫힌 구간 안에서 발생한 건너뛰기는 늦게 와도 거절이다.
        api.post(kim, "/api/v1/patrols/" + p.id() + "/return-requests", "{\"returnReason\":\"NORMAL\",\"clientEventId\":\"" + UUID.randomUUID()
                + "\",\"occurredAt\":\"2026-10-15T01:34:50Z\"}").andExpect(status().isOk()).andExpect(jsonPath("$.activityStatus").value("RETURNING"));
        assertThat(jdbc.queryForObject("SELECT end_cause FROM patrol_solo_waits WHERE patrol_id = ?", String.class, p.id()))
                .isEqualTo("RETURN_REQUESTED");
        skip(kim, p, 2, UUID.randomUUID(), "2026-10-15T01:31:00Z").andExpect(jsonPath("$.details.reason").value("SOLO_WAITING"));
        api.get(kim, "/api/v1/patrols/" + p.id()).andExpect(jsonPath("$.soloWaiting").value(Matchers.nullValue()));
        // 대기 구간은 고칠 수 없다.
        assertThatThrownBy(() -> jdbc.update("UPDATE patrol_solo_waits SET started_at = started_at - interval '1 minute' WHERE patrol_id = ?",
                p.id())).hasMessageContaining("대기 구간");
    }

    @Test
    void waitEndsByJoiningTransferOrReturnWithTheCauseRecorded() throws Exception {
        PatrolFixture.Patrol a = f.patrol(2);
        PatrolFixture.Patrol b = f.patrol(2);
        PatrolFixture.Patrol c = f.patrol(2);
        PatrolFixture.Patrol e = f.patrol(2);
        for (PatrolFixture.Patrol p : java.util.List.of(a, b, c, e)) {
            start(p, 0);
            start(p, 1);
        }
        confirm(a, 1, "ABORT");
        clock.advance(Duration.ofMinutes(1));
        // 다른 조원이 합류하면 대기가 끝나고 점검을 다시 한다.
        transfer(b, 1, a).andExpect(status().isOk());
        assertThat(cause(a)).isEqualTo("TEAMMATE_JOINED");
        skip(a.users().get(0), a, 0, UUID.randomUUID(), "2026-10-15T01:31:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.nextAction").value("NEXT_STOP"));
        // 대기 중인 조원이 다른 조로 이동하면 이전 조는 끝나고 대기 종료 원인이 남는다.
        confirm(c, 1, "ABORT");
        transfer(c, 0, a).andExpect(jsonPath("$.source.activityStatus").value("ENDED"));
        assertThat(cause(c)).isEqualTo("MEMBER_TRANSFERRED");
        // 대기 중인 조원의 복귀 확인: 조 종료, 원인 MEMBER_RETURNED.
        confirm(e, 1, "ABORT");
        confirm(e, 0, "ABORT").andExpect(jsonPath("$.activityStatus").value("ENDED"));
        assertThat(cause(e)).isEqualTo("MEMBER_RETURNED");
    }

    @Test
    void pauseIsPersonalOrderedByOccurrenceAndNeverBlocksOtherActions() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        start(p, 0);
        UUID kim = p.users().get(0);
        String body = pause(kim, p, "PAUSE_STARTED", "2026-10-15T01:20:00Z").andExpect(jsonPath("$.paused").value(true))
                .andReturn().getResponse().getContentAsString();
        assertMatches("PauseResult", body);
        // 다른 조원의 쉬기 값은 따로다.
        api.get(f.manager, "/api/v1/admin/patrols/" + p.id()).andExpect(jsonPath("$.members[0].paused").value(true))
                .andExpect(jsonPath("$.members[1].paused").value(false));
        // 순서가 뒤바뀐 늦은 이벤트(01:10)는 이력만, 현재 값은 더 늦은 발생 시각 기준.
        pause(kim, p, "PAUSE_ENDED", "2026-10-15T01:10:00Z").andExpect(jsonPath("$.paused").value(true));
        assertThat(events(p, "PAUSE_ENDED")).isEqualTo(1);
        // 쉬는 중에도 건너뛰기를 막지 않는다(누르지 않아도 불이익 없음).
        skip(kim, p, 0, UUID.randomUUID(), "2026-10-15T01:21:00Z").andExpect(status().isOk());
        pause(kim, p, "PAUSE_ENDED", "2026-10-15T01:25:00Z").andExpect(jsonPath("$.paused").value(false));
        // 시작 전 순회의 쉬기는 상태를 바꾸지 않는다.
        PatrolFixture.Patrol q = f.patrol(2, PatrolFixture.START.plusSeconds(4 * 3600), PatrolFixture.END.plusSeconds(4 * 3600));
        pause(q.users().get(0), q, "PAUSE_STARTED", "2026-10-15T01:20:00Z").andExpect(jsonPath("$.paused").value(false));
    }

    @Test
    void assignmentRefIsOptionalOutsideTheHashAndOtherReferencesAreNeverReplayed() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        PatrolFixture.Patrol other = f.patrol(2, PatrolFixture.START.plusSeconds(4 * 3600), PatrolFixture.END.plusSeconds(4 * 3600));
        UUID kim = p.users().get(0);
        UUID mine = memberId(p, 0);
        UUID foreign = memberId(other, 0);
        // 0.2.6 형식(참조 없음) 첫 요청과, 같은 ID에 참조를 붙이거나 뺀 재전송은 최초 결과를 재현한다.
        UUID event = UUID.randomUUID();
        String first = startWith(kim, p, event, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        startWith(kim, p, event, mine).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        startWith(kim, p, event, null).andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.startedAt").value(com.jayway.jsonpath.JsonPath.<String>read(first, "$.startedAt")));
        // 다른 배정을 가리키는 재전송은 같은 요청으로 재현하지 않는다.
        startWith(kim, p, event, foreign).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 첫 요청의 잘못된 참조는 400이고 이벤트를 남기지 않는다. 맞는 참조는 저장되고 참여 구간이 연결된다.
        api.post(kim, "/api/v1/patrols/" + p.id() + "/attendance-requests", "{\"clientEventId\":\"" + UUID.randomUUID()
                + "\",\"occurredAt\":\"2026-10-15T01:05:00Z\",\"assignmentRef\":\"" + foreign + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("assignmentRef"));
        assertThat(events(p, "ATTENDANCE_REQUESTED")).isZero();
        UUID withRef = UUID.randomUUID();
        api.post(kim, "/api/v1/patrols/" + p.id() + "/attendance-requests", "{\"clientEventId\":\"" + withRef
                + "\",\"occurredAt\":\"2026-10-15T01:05:00Z\",\"assignmentRef\":\"" + mine + "\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT assignment_ref = ? AND participation_id IS NOT NULL FROM patrol_events WHERE client_event_id = ?",
                Boolean.class, mine, withRef)).isTrue();
    }

    @Test
    void eventsThatHappenedBeforeATransferAreAcceptedForTheOldTeamOnly() throws Exception {
        PatrolFixture.Patrol a = f.patrol(3);
        PatrolFixture.Patrol b = f.patrol(2);
        start(a, 0);
        start(a, 2);
        start(b, 0);
        UUID park = a.users().get(1);
        UUID oldRef = memberId(a, 1);
        // 박○○는 시작 버튼을 누르지 않은 채 01:30에 2조로 이동했다. 01:05 시작·01:20 건너뛰기는 오프라인으로 남아 있었다.
        transfer(a, 1, b).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(5));
        UUID skipEvent = UUID.randomUUID();
        api.post(park, "/api/v1/patrols/" + a.id() + "/stops/" + a.stops().get(0) + "/skip-events", "{\"reason\":\"UNSAFE\",\"clientEventId\":\""
                + skipEvent + "\",\"occurredAt\":\"2026-10-15T01:20:00Z\",\"assignmentRef\":\"" + oldRef + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.visitStatus").value("SKIPPED"))
                .andExpect(jsonPath("$.nextAction").value("RETURN_TO_CENTER"));
        assertThat(jdbc.queryForObject("""
                SELECT pp.leave_reason FROM patrol_events e JOIN patrol_participations pp ON pp.id = e.participation_id WHERE e.client_event_id = ?""",
                String.class, skipEvent)).isEqualTo("TRANSFERRED");
        // 재전송은 최초 결과, 이동 뒤에 발생한 이벤트는 이전 조에 남기지 않는다.
        api.post(park, "/api/v1/patrols/" + a.id() + "/stops/" + a.stops().get(0) + "/skip-events", "{\"reason\":\"UNSAFE\",\"clientEventId\":\""
                + skipEvent + "\",\"occurredAt\":\"2026-10-15T01:20:00Z\"}").andExpect(jsonPath("$.duplicate").value(true));
        skip(park, a, 1, UUID.randomUUID(), "2026-10-15T01:31:00Z").andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("MEMBER_TRANSFERRED"));
        // 이동 전 시작: 이전 조 행은 SELF, 새 조의 빈 시작은 CARRIED로 채운다. 새 조 출발 시각은 바뀌지 않는다.
        String departedB = departed(b);
        api.post(park, "/api/v1/patrols/" + a.id() + "/activity-starts", "{\"clientEventId\":\"" + UUID.randomUUID()
                + "\",\"occurredAt\":\"2026-10-15T01:05:00Z\"}").andExpect(status().isOk()).andExpect(jsonPath("$.startSource").value("SELF"));
        assertThat(jdbc.queryForList("SELECT start_source FROM patrol_members WHERE participant_id = ? ORDER BY patrol_id = ? DESC", String.class,
                a.participants().get(1), a.id())).containsExactly("SELF", "CARRIED");
        assertThat(departed(b)).isEqualTo(departedB);
        // 이동 전 복귀 요청은 이력만: 새 조에 복귀 요청을 만들지 않는다.
        api.post(park, "/api/v1/patrols/" + a.id() + "/return-requests", "{\"returnReason\":\"ABORT\",\"abortReason\":\"HEALTH\",\"clientEventId\":\""
                + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:25:00Z\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT requested_return_reason FROM patrol_members WHERE patrol_id = ? AND participant_id = ?",
                String.class, b.id(), a.participants().get(1))).isNull();
    }

    @Test
    void returnRequestFromAnEarlierIntervalOfTheSameTeamIsHistoryOnly() throws Exception {
        // 리뷰 재현(06ef918): A→B→A 뒤 A 첫 참여 구간(01:10)의 복귀 요청이 도착하면 현재 개인 복귀 요청에 저장됐다.
        PatrolFixture.Patrol a = f.patrol(3);
        PatrolFixture.Patrol b = f.patrol(2);
        for (int i = 0; i < 3; i++) {
            start(a, i);
        }
        start(b, 0);
        start(b, 1);
        UUID park = a.users().get(1);
        transfer(a, 1, b).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(1));
        api.post(f.manager, "/api/v1/admin/patrols/" + b.id() + "/members/" + a.participants().get(1) + "/transfers",
                "{\"targetPatrolId\":\"" + a.id() + "\",\"sourceVersion\":" + version(b) + ",\"targetVersion\":" + version(a)
                        + ",\"reason\":\"원래 조로 합류\"}", UUID.randomUUID()).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(1));
        long revision = jdbc.queryForObject("SELECT revision FROM patrols WHERE id = ?", Long.class, a.id());
        UUID event = UUID.randomUUID();
        String body = "{\"returnReason\":\"NORMAL\",\"clientEventId\":\"" + event + "\",\"occurredAt\":\"2026-10-15T01:10:00Z\"}";
        String first = api.post(park, "/api/v1/patrols/" + a.id() + "/return-requests", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS")).andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        assertMatches("ReturnResult", first);
        // 이전 구간의 요청은 이력만: 현재 개인 복귀 요청·조 상태·revision은 그대로다.
        assertThat(jdbc.queryForObject("SELECT requested_return_reason FROM patrol_members WHERE patrol_id = ? AND participant_id = ?",
                String.class, a.id(), a.participants().get(1))).isNull();
        assertThat(jdbc.queryForObject("SELECT revision FROM patrols WHERE id = ?", Long.class, a.id())).isEqualTo(revision);
        assertThat(jdbc.queryForObject("""
                SELECT pp.leave_reason FROM patrol_events e JOIN patrol_participations pp ON pp.id = e.participation_id WHERE e.client_event_id = ?""",
                String.class, event)).isEqualTo("TRANSFERRED");
        // 같은 이벤트 재전송은 최초 응답을 재현한다.
        String replay = api.post(park, "/api/v1/patrols/" + a.id() + "/return-requests", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(replay.replace("\"duplicate\":true", "\"duplicate\":false")).isEqualTo(first);
        // 지금 구간(재합류 01:31 이후)에 발생한 요청은 현재 개인 요청으로 저장된다.
        api.post(park, "/api/v1/patrols/" + a.id() + "/return-requests", "{\"returnReason\":\"ABORT\",\"abortReason\":\"HEALTH\",\"clientEventId\":\""
                + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:32:00Z\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT requested_return_reason FROM patrol_members WHERE patrol_id = ? AND participant_id = ?",
                String.class, a.id(), a.participants().get(1))).isEqualTo("ABORT");
    }

    @Test
    void rejoiningSeparatesTheCurrentReturnRequestFromTheEarlierIntervalOne() throws Exception {
        // 리뷰 재현(c69087e): A에서 NORMAL 요청 → A→B→A → 새 ABORT/HEALTH 요청이 200인데 개인 요청값은 NORMAL로 남았다.
        PatrolFixture.Patrol a = f.patrol(3);
        PatrolFixture.Patrol b = f.patrol(2);
        for (int i = 0; i < 3; i++) {
            start(a, i);
        }
        start(b, 0);
        start(b, 1);
        UUID park = a.users().get(1);
        UUID firstEvent = UUID.randomUUID();
        String firstBody = "{\"returnReason\":\"NORMAL\",\"clientEventId\":\"" + firstEvent + "\",\"occurredAt\":\"2026-10-15T01:10:00Z\"}";
        String first = api.post(park, "/api/v1/patrols/" + a.id() + "/return-requests", firstBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.returnReason").value("NORMAL")).andReturn().getResponse().getContentAsString();
        transfer(a, 1, b).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(1));
        api.post(f.manager, "/api/v1/admin/patrols/" + b.id() + "/members/" + a.participants().get(1) + "/transfers",
                "{\"targetPatrolId\":\"" + a.id() + "\",\"sourceVersion\":" + version(b) + ",\"targetVersion\":" + version(a)
                        + ",\"reason\":\"원래 조로 합류\"}", UUID.randomUUID()).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(1));
        // 재합류 뒤 현재 구간에는 아직 복귀 요청이 없다. 이전 요청은 RETURNING 판정에 쓰지 않는다.
        assertThat(requested(a, 1)).isEqualTo("-:-");
        for (int i : new int[] {0, 2}) {
            api.post(a.users().get(i), "/api/v1/patrols/" + a.id() + "/return-requests", "{\"returnReason\":\"NORMAL\",\"clientEventId\":\""
                    + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:32:00Z\"}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"));
        }
        // 이전 구간 요청의 재전송은 최초 응답 재현, 이전 구간의 다른 늦은 요청은 이력만.
        assertThat(api.post(park, "/api/v1/patrols/" + a.id() + "/return-requests", firstBody).andExpect(jsonPath("$.duplicate").value(true))
                .andReturn().getResponse().getContentAsString().replace("\"duplicate\":true", "\"duplicate\":false")).isEqualTo(first);
        api.post(park, "/api/v1/patrols/" + a.id() + "/return-requests", "{\"returnReason\":\"ABORT\",\"abortReason\":\"DEVICE\",\"clientEventId\":\""
                + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:15:00Z\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.activityStatus").value("IN_PROGRESS"));
        assertThat(requested(a, 1)).isEqualTo("-:-");
        // 재합류 뒤 처음 보낸 요청은 현재 구간에 반영되고, 현장 조원 전원이 요청했으므로 RETURNING.
        api.post(park, "/api/v1/patrols/" + a.id() + "/return-requests", "{\"returnReason\":\"ABORT\",\"abortReason\":\"HEALTH\",\"clientEventId\":\""
                + UUID.randomUUID() + "\",\"occurredAt\":\"2026-10-15T01:32:00Z\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.returnReason").value("ABORT")).andExpect(jsonPath("$.abortReason").value("HEALTH"))
                .andExpect(jsonPath("$.activityStatus").value("RETURNING"));
        assertThat(requested(a, 1)).isEqualTo("ABORT:HEALTH");
        // 이전 구간의 요청은 그 구간 기록과 이벤트로 남는다(삭제하지 않음).
        assertThat(jdbc.queryForObject("""
                SELECT requested_return_reason FROM patrol_participations WHERE patrol_id = ? AND participant_id = ? AND leave_reason = 'TRANSFERRED'""",
                String.class, a.id(), a.participants().get(1))).isEqualTo("NORMAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ? AND actor_user_id = ? AND type = 'RETURN_REQUESTED'",
                Long.class, a.id(), park)).isEqualTo(3);
    }

    private String requested(PatrolFixture.Patrol p, int index) {
        return jdbc.queryForObject("""
                SELECT coalesce(requested_return_reason, '-') || ':' || coalesce(requested_abort_reason, '-') FROM patrol_members
                WHERE patrol_id = ? AND participant_id = ?""", String.class, p.id(), p.participants().get(index));
    }

    @Test
    void skipIsDeferredWhileAPhotoIsProcessing() throws Exception {
        PatrolFixture.Patrol p = f.patrol(2);
        start(p, 0);
        UUID slot = p.slots().get(0);
        UUID inspection = f.id("""
                INSERT INTO inspections (program_id, capture_slot_id, submitted_by, client_submission_id, attempt_id, request_hash,
                    submission_sha256, attempt_no, captured_at, masking_version, device_masked, device_mask_report_json)
                VALUES (?, ?, ?, ?, ?, '\\x00'::bytea, decode(repeat('00', 32), 'hex'), 1, ?, 'web-mask-0.1.0', true, '{"faces":0}')
                RETURNING id""", f.program, slot, p.users().get(1), UUID.randomUUID(), UUID.randomUUID(),
                PatrolFixture.ts(java.time.Instant.parse("2026-10-15T01:15:00Z")));
        jdbc.update("UPDATE capture_slots SET active_inspection_id = ? WHERE id = ?", inspection, slot);
        skip(p.users().get(0), p, 0, UUID.randomUUID(), "2026-10-15T01:20:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.visitStatus").value("PENDING")).andExpect(jsonPath("$.stopUpdated").value(false));
        assertThat(jdbc.queryForObject("SELECT skip_pending_event_id IS NOT NULL FROM patrol_stops WHERE id = ?", Boolean.class, p.stops().get(0)))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT resolution FROM capture_slots WHERE id = ?", String.class, slot)).isEqualTo("OPEN");
        api.get(p.users().get(0), "/api/v1/patrols/" + p.id()).andExpect(jsonPath("$.stops[0].skipPending").value(true));
    }

    private ResultActions skip(UUID user, PatrolFixture.Patrol p, int stop, UUID event, String at) throws Exception {
        return api.post(user, "/api/v1/patrols/" + p.id() + "/stops/" + p.stops().get(stop) + "/skip-events",
                "{\"reason\":\"UNSAFE\",\"clientEventId\":\"" + event + "\",\"occurredAt\":\"" + at + "\"}");
    }

    private ResultActions pause(UUID user, PatrolFixture.Patrol p, String type, String at) throws Exception {
        return api.post(user, "/api/v1/patrols/" + p.id() + "/pause-events",
                "{\"type\":\"" + type + "\",\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"" + at + "\"}");
    }

    private ResultActions startWith(UUID user, PatrolFixture.Patrol p, UUID event, UUID ref) throws Exception {
        return api.post(user, "/api/v1/patrols/" + p.id() + "/activity-starts", "{\"clientEventId\":\"" + event
                + "\",\"occurredAt\":\"2026-10-15T01:00:00Z\"" + (ref == null ? "" : ",\"assignmentRef\":\"" + ref + "\"") + "}");
    }

    private void start(PatrolFixture.Patrol p, int index) throws Exception {
        startWith(p.users().get(index), p, UUID.randomUUID(), null).andExpect(status().isOk());
    }

    private ResultActions confirm(PatrolFixture.Patrol p, int index, String reason) throws Exception {
        return api.post(f.manager, "/api/v1/admin/patrols/" + p.id() + "/return-confirmations", "{\"participantId\":\""
                + p.participants().get(index) + "\",\"returnedAt\":\"2026-10-15T01:25:00Z\",\"returnReason\":\"" + reason + "\""
                + ("ABORT".equals(reason) ? ",\"abortReason\":\"HEALTH\"" : "") + ",\"version\":" + version(p) + "}", UUID.randomUUID());
    }

    private ResultActions transfer(PatrolFixture.Patrol from, int index, PatrolFixture.Patrol to) throws Exception {
        return api.post(f.manager, "/api/v1/admin/patrols/" + from.id() + "/members/" + from.participants().get(index) + "/transfers",
                "{\"targetPatrolId\":\"" + to.id() + "\",\"sourceVersion\":" + version(from) + ",\"targetVersion\":" + version(to)
                        + ",\"reason\":\"결원으로 합류\"}", UUID.randomUUID());
    }

    private String cause(PatrolFixture.Patrol p) {
        return jdbc.queryForObject("SELECT end_cause FROM patrol_solo_waits WHERE patrol_id = ? ORDER BY started_at DESC LIMIT 1", String.class,
                p.id());
    }

    private UUID memberId(PatrolFixture.Patrol p, int index) {
        return jdbc.queryForObject("SELECT id FROM patrol_members WHERE patrol_id = ? AND participant_id = ?", UUID.class, p.id(),
                p.participants().get(index));
    }

    private long version(PatrolFixture.Patrol p) {
        return jdbc.queryForObject("SELECT version FROM patrols WHERE id = ?", Long.class, p.id());
    }

    private long events(PatrolFixture.Patrol p, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM patrol_events WHERE patrol_id = ? AND type = ?", Long.class, p.id(), type);
    }

    private String departed(PatrolFixture.Patrol p) {
        OffsetDateTime at = jdbc.queryForObject("SELECT departed_at FROM patrols WHERE id = ?", OffsetDateTime.class, p.id());
        return at == null ? null : at.toInstant().toString();
    }
}
