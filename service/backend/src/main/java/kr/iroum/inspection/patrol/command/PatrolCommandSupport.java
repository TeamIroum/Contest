package kr.iroum.inspection.patrol.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import kr.iroum.inspection.common.config.TimeConfig;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse;
import kr.iroum.inspection.patrol.PatrolReader;
import kr.iroum.inspection.patrol.PatrolReader.MemberRow;
import kr.iroum.inspection.patrol.PatrolReader.PatrolRow;
import tools.jackson.databind.json.JsonMapper;

/**
 * 순회 명령 공통: 잠금·버전·이벤트 멱등·시각 검증·조 상태 판정(업무 API 6~8절, D-24, D-30, D-33, D-36, D-37).
 * 모든 명령은 순회 행을 먼저 잠그고(잠금 단계 6) 배정 행을 잠근 뒤(단계 8) 상태를 다시 읽어 검사한다.
 */
@Component
public class PatrolCommandSupport {

    private static final String NOT_FOUND = "찾을 수 없거나 접근할 수 없는 항목입니다.";

    private final JdbcTemplate jdbc;
    private final PatrolReader reader;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration clockSkew;

    public PatrolCommandSupport(JdbcTemplate jdbc, PatrolReader reader, JsonMapper json, Clock clock,
            @Value("${inspection.activity.clock-skew-tolerance:5m}") Duration clockSkew) {
        this.jdbc = jdbc;
        this.reader = reader;
        this.json = json;
        this.clock = clock;
        this.clockSkew = clockSkew;
    }

    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 순회와 배정 행을 잠그고 최신 상태를 읽는다. */
    public PatrolRow lock(UUID patrolId) {
        PatrolRow patrol = reader.lockPatrol(patrolId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND));
        reader.lockMembers(patrolId);
        return patrol;
    }

    public List<MemberRow> members(UUID patrolId) {
        return reader.members(patrolId);
    }

    /** 배정 중 조원. 이 조에서 이동해 나간 조원이면 409 MEMBER_TRANSFERRED(현재 조에서 처리), 없으면 404. */
    public MemberRow activeMember(PatrolRow patrol, UUID participantId) {
        return reader.members(patrol.id()).stream().filter(m -> m.participantId().equals(participantId)).findFirst()
                .orElseThrow(() -> reader.transferredTo(patrol.id(), participantId).map(to -> transferred(patrol, to))
                        .orElseGet(() -> new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND)));
    }

    public ApiException transferred(PatrolRow patrol, UUID transferredTo) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("reason", "MEMBER_TRANSFERRED");
        details.put("activityStatus", patrol.status());
        details.put("transferredToPatrolId", transferredTo);
        return new ApiException(ErrorCode.ACTIVITY_STATE_CONFLICT, "다른 조로 옮긴 조원이에요. 지금 배정된 조에서 처리해 주세요.", details);
    }

    /** 배정 중이 아닌 행까지 포함한 배정 행. */
    public MemberRow memberRow(UUID patrolId, UUID participantId) {
        return reader.memberRow(patrolId, participantId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND));
    }

    public MemberRow member(UUID patrolId, UUID participantId) {
        return reader.members(patrolId).stream().filter(m -> m.participantId().equals(participantId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND));
    }

    public PatrolRow reload(UUID patrolId) {
        return reader.patrol(patrolId).orElseThrow();
    }

    public void requireNotCancelled(PatrolRow patrol) {
        if ("CANCELLED".equals(patrol.status())) {
            throw new ApiException(ErrorCode.PATROL_CANCELLED, "취소된 활동입니다.");
        }
    }

    public void requireVersion(PatrolRow patrol, long version) {
        if (patrol.version() != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "다른 사람이 먼저 바꿨습니다. 최신 내용을 확인해 주세요.",
                    Map.of("currentVersion", patrol.version()));
        }
    }

    public ApiException stateConflict(String reason, PatrolRow patrol, String message) {
        return new ApiException(ErrorCode.ACTIVITY_STATE_CONFLICT, message, Map.of("reason", reason, "activityStatus", patrol.status()));
    }

    /** 참여 가능 인원: 배정 중이고 결석(ABSENT)으로 확정되지 않은 조원(D-30). */
    public static long availableMembers(List<MemberRow> members) {
        return members.stream().filter(m -> !m.absent()).count();
    }

    /**
     * 출발 확인 조건(기능정의서 ACT-07·ACC-02): 출석이 확정(PRESENT)된 조원이 2명 이상이어야 한다.
     * (service/: 원본은 '결석이 아닌 조원 2명'만 보아 출석 확인 전에도 출발 확인이 됐다)
     */
    public void requirePresentPair(PatrolRow patrol, List<MemberRow> members) {
        if (members.stream().filter(m -> "PRESENT".equals(m.attendance())).count() < 2) {
            throw stateConflict("ATTENDANCE_SHORT", patrol, "출석이 확인된 조원이 2명 이상이어야 출발을 확인할 수 있어요.");
        }
    }

    /** 1인 출발 방지(D-30): 담당자 출발 확인·활동 시작 모두에 적용한다. */
    public void requireNotSolo(PatrolRow patrol, List<MemberRow> members) {
        if (availableMembers(members) < 2) {
            throw stateConflict("SOLO_DEPARTURE", patrol, "함께 출발할 조원이 없어요. 활동 담당자에게 조 편성을 확인해 주세요.");
        }
    }

    /** 활동 시각: 서버 시각보다 허용 오차를 넘어 미래이거나 활동일(KST) 밖이면 422. */
    public void requireActivityTime(PatrolRow patrol, Instant at) {
        if (at == null) {
            return;
        }
        boolean future = at.isAfter(clock.instant().plus(clockSkew));
        boolean otherDay = !at.atZone(TimeConfig.ACTIVITY_ZONE).toLocalDate().equals(patrol.activityDate());
        if (future || otherDay) {
            throw new ApiException(ErrorCode.INVALID_ACTIVITY_TIME, "활동 시각을 확인해 주세요.");
        }
    }

    public long bumpRevision(UUID patrolId) {
        return jdbc.queryForObject("UPDATE patrols SET revision = revision + 1, updated_at = now() WHERE id = ? RETURNING revision",
                Long.class, patrolId);
    }

    public long bumpVersion(UUID patrolId) {
        return jdbc.queryForObject("""
                UPDATE patrols SET version = version + 1, revision = revision + 1, updated_at = now() WHERE id = ? RETURNING version""",
                Long.class, patrolId);
    }

    /**
     * departedAt = 이 조에서 기록한 개인 시작의 최솟값(업무 API 6.2절). 이동해 나간 조원의 시작도 남기고(D-36),
     * 다른 조에서 이어받은 시작(CARRIED)과 출발 전 재편성으로 빠진 배정은 넣지 않는다(설계안 4.4).
     */
    public void recomputeDepartedAt(UUID patrolId) {
        jdbc.update("""
                UPDATE patrols SET departed_at = (
                    SELECT min(m.started_at) FROM patrol_members m
                    WHERE m.patrol_id = ? AND m.start_source IN ('SELF', 'ADMIN_CORRECTION')
                      AND (m.assignment_active OR EXISTS (SELECT 1 FROM patrol_participations pp WHERE pp.patrol_id = m.patrol_id
                          AND pp.participant_id = m.participant_id AND pp.leave_reason = 'TRANSFERRED')))
                WHERE id = ?""", patrolId, patrolId);
    }

    /**
     * 시니어 이벤트의 배정 확인(설계안 4.7·4.8). 이벤트 처리(action) 맨 앞에서 부른다. 거절하면 롤백으로 이벤트도 남지 않는다.
     * <ol>
     * <li>assignmentRef가 있으면 이 사용자·이 조의 배정 행이어야 한다(아니면 422). 시각을 증명하는 값은 아니다.</li>
     * <li>발생 시각이 들어가는 참여 구간(배정 중 구간 또는 이동으로 닫힌 구간)을 고른다. 경계는 기기 시각을 그대로 비교한다.</li>
     * <li>이동해 나간 뒤 발생한 이벤트는 409 MEMBER_TRANSFERRED, 이 조에 합류하기 전 시각은 422 INVALID_ACTIVITY_TIME.</li>
     * </ol>
     * 고른 구간 ID를 이벤트 행에 남기고 돌려준다.
     */
    public UUID resolveParticipation(PatrolRow patrol, kr.iroum.inspection.auth.access.AccessPolicy.SeniorAssignment me, UUID actor,
            UUID clientEventId, UUID assignmentRef, Instant occurredAt) {
        UUID found = participationAt(patrol, me, assignmentRef, occurredAt);
        jdbc.update("UPDATE patrol_events SET participation_id = ? WHERE actor_user_id = ? AND client_event_id = ?", found, actor,
                clientEventId);
        return found;
    }

    /** resolveParticipation의 판정만(기록 없음). 사진 촬영 시각의 참여 구간 판정에도 쓴다(설계안 7절). */
    public UUID participationAt(PatrolRow patrol, kr.iroum.inspection.auth.access.AccessPolicy.SeniorAssignment me, UUID assignmentRef,
            Instant occurredAt) {
        if (assignmentRef != null && !assignmentRef.equals(me.memberId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, List.of(new ErrorResponse.FieldError(
                    "assignmentRef", "INVALID_FORMAT", "이 순회의 내 배정이 아닙니다. 최신 순회 정보를 받아 주세요.")));
        }
        Optional<UUID> found = jdbc.query("""
                SELECT id FROM patrol_participations
                WHERE patrol_id = ? AND participant_id = ? AND (leave_reason IS NULL OR leave_reason = 'TRANSFERRED')
                  AND (joined_at IS NULL OR joined_at <= ?) AND (left_at IS NULL OR ? < left_at)
                ORDER BY created_at DESC LIMIT 1""", (rs, i) -> rs.getObject(1, UUID.class), patrol.id(), me.participantId(),
                Timestamp.from(occurredAt), Timestamp.from(occurredAt)).stream().findFirst();
        if (found.isEmpty()) {
            if (!me.active()) {
                throw transferred(patrol, reader.transferredTo(patrol.id(), me.participantId()).orElse(null));
            }
            throw new ApiException(ErrorCode.INVALID_ACTIVITY_TIME, "이 조에 합류하기 전 시각이에요. 활동 시각을 확인해 주세요.");
        }
        return found.get();
    }

    /** 참여 구간이 지금 열린 구간(현재 배정)인지. 같은 조의 이전 구간(A→B→A의 첫 A)이면 false. */
    public boolean isOpenParticipation(UUID participationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT leave_reason IS NULL FROM patrol_participations WHERE id = ?", Boolean.class,
                participationId));
    }

    /** 발생 시각이 이 조원의 대기 구간 [시작, 종료) 안이면 true(D-35·D-38: 현재 대기 여부가 아니라 발생 시각 기준). */
    public boolean waitingAt(UUID patrolId, UUID participantId, Instant at) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM patrol_solo_waits WHERE patrol_id = ? AND participant_id = ? AND started_at <= ?
                    AND (ended_at IS NULL OR ? < ended_at))""", Boolean.class, patrolId, participantId, Timestamp.from(at), Timestamp.from(at)));
    }

    /** 지금 열린 대기(조당 하나). */
    public record WaitRow(UUID id, UUID participantId, Instant startedAt) {
    }

    public Optional<WaitRow> openWait(UUID patrolId) {
        return jdbc.query("SELECT id, participant_id, started_at FROM patrol_solo_waits WHERE patrol_id = ? AND ended_at IS NULL",
                (rs, i) -> new WaitRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, java.time.OffsetDateTime.class).toInstant()), patrolId).stream().findFirst();
    }

    /**
     * 혼자 남은 대기(D-35, 설계안 5.1): 진행 중(IN_PROGRESS·RETURNING)이고 현장 조원이 1명이며 그 사람이 복귀 요청을 보내지 않았으면 대기.
     * 조건이 생기면 서버 시각으로 열고(소급하지 않음) 없어지면 원인을 남겨 닫는다. 쉬기는 대기에 영향을 주지 않는다(D-38).
     */
    private void syncWait(PatrolRow patrol, List<MemberRow> field, String status) {
        Optional<WaitRow> open = openWait(patrol.id());
        boolean running = "IN_PROGRESS".equals(status) || "RETURNING".equals(status);
        MemberRow alone = running && field.size() == 1 && field.get(0).requestedReturnReason() == null ? field.get(0) : null;
        if (open.isPresent() && (alone == null || !alone.participantId().equals(open.get().participantId()))) {
            closeWait(patrol.id(), open.get(), field);
            open = Optional.empty();
        }
        if (alone != null && open.isEmpty()) {
            jdbc.update("INSERT INTO patrol_solo_waits (program_id, patrol_id, participant_id, started_at) VALUES (?, ?, ?, ?)",
                    patrol.programId(), patrol.id(), alone.participantId(), Timestamp.from(now()));
        }
    }

    private void closeWait(UUID patrolId, WaitRow wait, List<MemberRow> field) {
        MemberRow waiter = reader.members(patrolId).stream().filter(m -> m.participantId().equals(wait.participantId())).findFirst()
                .orElse(null);
        String cause;
        if (waiter == null) {
            cause = "MEMBER_TRANSFERRED";
        } else if (waiter.returned()) {
            cause = "MEMBER_RETURNED";
        } else if (waiter.requestedReturnReason() != null) {
            cause = "RETURN_REQUESTED";
        } else if (field.isEmpty()) {
            cause = "PATROL_ENDED";
        } else {
            Boolean joined = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM patrol_participations WHERE patrol_id = ? AND leave_reason IS NULL AND joined_at >= ?)""",
                    Boolean.class, patrolId, Timestamp.from(wait.startedAt()));
            cause = Boolean.TRUE.equals(joined) ? "TEAMMATE_JOINED" : "ATTENDANCE_CORRECTED";
        }
        jdbc.update("UPDATE patrol_solo_waits SET ended_at = ?, end_cause = ? WHERE id = ?", Timestamp.from(now()), cause, wait.id());
    }

    /** 종료 기록의 계기(trigger_type). 현장 조원을 없앨 수 있는 처리만 조를 끝낸다. */
    public enum EndTrigger {
        LAST_RETURN_CONFIRMED, LAST_MEMBER_TRANSFERRED, ABSENT_CONFIRMED
    }

    /**
     * 조 상태 판정(설계안 3.2). 상태를 바꿀 수 있는 모든 명령이 같은 트랜잭션·순회 잠금 아래 마지막에 부른다.
     * <ol>
     * <li>CANCELLED·ENDED는 바꾸지 않는다. 종료 뒤 출결·복귀 확인 보완은 저장하되 활동을 다시 열지 않는다(D-37).</li>
     * <li>현장 조원(배정 중·ABSENT 아님·복귀 확인 전)이 0명이고 복귀·이동으로 나간 사람이 있으면 ENDED로 끝내고 종료 기록을 남긴다.</li>
     * <li>현장 조원이 0명인데 나간 사람이 없으면(전원 결석) 그대로 둔다. 0명 자동 완료는 하지 않는다(D-30).</li>
     * <li>진행은 앞으로만 간다: ASSIGNED→IN_PROGRESS(개인 시작·복귀 확인), IN_PROGRESS→RETURNING(현장 조원 전원 본인 복귀 요청).</li>
     * </ol>
     * 끝냈으면 true.
     */
    public boolean evaluate(UUID patrolId, UUID actor, EndTrigger trigger, UUID triggerParticipant) {
        PatrolRow patrol = reload(patrolId);
        if (patrol.terminal()) {
            return false;
        }
        List<MemberRow> members = reader.members(patrolId);
        List<MemberRow> field = members.stream().filter(MemberRow::inField).toList();
        int normal = (int) members.stream().filter(m -> m.returned() && "NORMAL".equals(m.returnReason())).count();
        int abort = (int) members.stream().filter(m -> m.returned() && "ABORT".equals(m.returnReason())).count();
        int transferred = transferredOut(patrolId);
        if (field.isEmpty()) {
            if (normal + abort + transferred == 0) {
                return false;
            }
            end(patrol, members, normal, abort, transferred, actor, trigger, triggerParticipant);
            return true;
        }
        String next = patrol.status();
        boolean trace = transferred > 0 || members.stream().anyMatch(m -> m.startedAt() != null || m.returned());
        if ("ASSIGNED".equals(next) && trace) {
            next = "IN_PROGRESS";
        }
        if ("IN_PROGRESS".equals(next) && field.stream().allMatch(m -> m.requestedReturnReason() != null)) {
            next = "RETURNING";
        }
        if (!next.equals(patrol.status())) {
            jdbc.update("UPDATE patrols SET activity_status = ? WHERE id = ?", next, patrolId);
        }
        syncWait(patrol, field, next);
        return false;
    }

    int transferredOut(UUID patrolId) {
        return reader.transferredOut(patrolId);
    }

    private void end(PatrolRow patrol, List<MemberRow> members, int normal, int abort, int transferred, UUID actor, EndTrigger trigger,
            UUID triggerParticipant) {
        if (trigger == null || actor == null || triggerParticipant == null) {
            throw new IllegalStateException("종료 계기 없이 조를 끝낼 수 없습니다: " + patrol.id());
        }
        Instant now = now();
        int absent = (int) members.stream().filter(MemberRow::absent).count();
        String reason = transferred == 0 ? "ALL_RETURNED" : normal + abort == 0 ? "ALL_TRANSFERRED" : "RETURNED_AND_TRANSFERRED";
        jdbc.update("""
                INSERT INTO patrol_end_records (patrol_id, program_id, ended_at, end_reason, trigger_type, trigger_participant_id, actor_user_id,
                    normal_returned, abort_returned, transferred_out, absent, record_source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'LIVE')""",
                patrol.id(), patrol.programId(), Timestamp.from(now), reason, trigger.name(), triggerParticipant, actor, normal, abort,
                transferred, absent);
        openWait(patrol.id()).ifPresent(w -> closeWait(patrol.id(), w, List.of()));
        jdbc.update("UPDATE patrols SET activity_status = 'ENDED', finished_at = ? WHERE id = ?", Timestamp.from(now), patrol.id());
        // 남은 장소는 자동 미방문으로 둔다. 종료 전 적법하게 촬영한 늦은 사진으로 보완할 수 있다(D-27, D-39).
        jdbc.update("""
                UPDATE patrol_stops SET visit_status = 'NOT_VISITED', not_visited_reason = 'TEAM_ENDED'
                WHERE patrol_id = ? AND visit_status = 'PENDING'""", patrol.id());
    }

    public record EventOutcome<T>(T result, boolean replayed) {
    }

    /**
     * 시니어 이벤트(clientEventId) 멱등 기록. 순회 잠금 뒤 호출한다.
     * 같은 사용자·clientEventId·같은 내용이면 저장한 최초 결과를 재현하고(상태 검사보다 먼저), 다른 내용이면 409다.
     * 새 이벤트는 행을 만든 뒤 action으로 상태를 바꾸며, action이 거절하면 트랜잭션 롤백으로 이벤트도 남지 않는다.
     */
    public <T> EventOutcome<T> event(UUID actor, PatrolRow patrol, UUID stopId, String type, String reasonCode, UUID clientEventId,
            Instant occurredAt, Object identity, Class<T> resultType, Supplier<T> action) {
        return event(actor, patrol, stopId, type, reasonCode, clientEventId, occurredAt, identity, null, null, resultType, action);
    }

    /**
     * assignmentRef(배정 행 ID)는 내용 해시에서 빼고 따로 저장한다(설계안 4.8). 재전송 때 내용 해시가 같아도, 보낸 참조가 NULL도 아니고
     * 이 사용자·이 조의 유효한 참조(validRef, 하나뿐)도 아니면 같은 요청으로 재현하지 않고 409로 거절한다.
     */
    public <T> EventOutcome<T> event(UUID actor, PatrolRow patrol, UUID stopId, String type, String reasonCode, UUID clientEventId,
            Instant occurredAt, Object identity, UUID assignmentRef, UUID validRef, Class<T> resultType, Supplier<T> action) {
        byte[] hash = hash(identity);
        UUID eventId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO patrol_events (id, program_id, patrol_id, patrol_stop_id, actor_user_id, client_event_id, request_hash, type,
                    reason_code, occurred_at, received_at, assignment_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (actor_user_id, client_event_id) DO NOTHING""",
                eventId, patrol.programId(), patrol.id(), stopId, actor, clientEventId, hash, type, reasonCode, Timestamp.from(occurredAt),
                Timestamp.from(now()), assignmentRef);
        if (inserted == 0) {
            Optional<Map<String, Object>> existing = jdbc.queryForList("""
                    SELECT request_hash, payload_json::text AS payload FROM patrol_events WHERE actor_user_id = ? AND client_event_id = ?""",
                    actor, clientEventId).stream().findFirst();
            if (existing.isEmpty() || !MessageDigest.isEqual((byte[]) existing.get().get("request_hash"), hash)
                    || existing.get().get("payload") == null || (assignmentRef != null && !assignmentRef.equals(validRef))) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "같은 이벤트 ID로 다른 내용이 이미 처리됐습니다.");
            }
            return new EventOutcome<>(json.readValue((String) existing.get().get("payload"), resultType), true);
        }
        T result = action.get();
        jdbc.update("UPDATE patrol_events SET payload_json = ?::jsonb WHERE id = ?", json.writeValueAsString(result), eventId);
        return new EventOutcome<>(result, false);
    }

    private byte[] hash(Object identity) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(identity).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
