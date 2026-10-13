-- 03단계 보완(2026-10-12): 혼자 남은 대기(D-35), 대기 전후 건너뛰기·쉬기(D-38), 건너뛴 구도의 기준 이벤트(D-39 준비),
-- 오프라인 이벤트의 배정 확인(assignmentRef). 근거: docs/03단계_API_DB_설계안.md 4.7·4.8·5절.

-- 1) 대기 구간: 늦게 도착한 이벤트·사진을 발생 시각으로 판정하려고 구간으로 남긴다. 시작은 서버가 조건을 기록한 시각이며
--    한 번 쓴 시각은 고치지 않는다(종료 시각을 한 번 채우는 것만 허용).
CREATE TABLE patrol_solo_waits (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id     uuid        NOT NULL REFERENCES programs (id),
    patrol_id      uuid        NOT NULL,
    participant_id uuid        NOT NULL,
    started_at     timestamptz NOT NULL,
    ended_at       timestamptz,
    end_cause      varchar(30) CHECK (end_cause IN ('TEAMMATE_JOINED', 'MEMBER_TRANSFERRED', 'MEMBER_RETURNED', 'RETURN_REQUESTED',
                                                    'PATROL_ENDED', 'ATTENDANCE_CORRECTED')),
    FOREIGN KEY (program_id, patrol_id) REFERENCES patrols (program_id, id),
    FOREIGN KEY (patrol_id, participant_id) REFERENCES patrol_members (patrol_id, participant_id),
    CHECK ((ended_at IS NULL) = (end_cause IS NULL)),
    CHECK (ended_at IS NULL OR started_at <= ended_at)
);
CREATE UNIQUE INDEX ux_patrol_solo_waits_open ON patrol_solo_waits (patrol_id) WHERE ended_at IS NULL;
CREATE INDEX ix_patrol_solo_waits_member ON patrol_solo_waits (patrol_id, participant_id, started_at);

CREATE FUNCTION patrol_solo_waits_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.started_at <> OLD.started_at OR NEW.participant_id <> OLD.participant_id OR NEW.patrol_id <> OLD.patrol_id
       OR OLD.ended_at IS NOT NULL THEN
        RAISE EXCEPTION '대기 구간은 종료 시각을 한 번 채우는 것 말고는 바꿀 수 없습니다' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER patrol_solo_waits_append_only BEFORE UPDATE OR DELETE ON patrol_solo_waits
    FOR EACH ROW EXECUTE FUNCTION patrol_solo_waits_append_only();

-- 2) 개인 쉬기(D-38): 조 단위 patrols.paused 대신 조원별 값. paused_changed_at은 반영한 이벤트의 발생 시각이다.
--    조 단위 컬럼은 지우지 않고 더 쓰지 않는다. 백필은 조원별 마지막 쉬기 이벤트(발생 시각 기준)로 하며 조 값을 복사하지 않는다.
ALTER TABLE patrol_members
    ADD COLUMN paused            boolean NOT NULL DEFAULT false,
    ADD COLUMN paused_changed_at timestamptz;

WITH last_pause AS (
    SELECT DISTINCT ON (e.patrol_id, e.actor_user_id) e.patrol_id, e.actor_user_id, e.type, e.occurred_at
    FROM patrol_events e WHERE e.type IN ('PAUSE_STARTED', 'PAUSE_ENDED')
    ORDER BY e.patrol_id, e.actor_user_id, e.occurred_at DESC, e.received_at DESC, e.id
)
UPDATE patrol_members m SET paused = (l.type = 'PAUSE_STARTED'), paused_changed_at = l.occurred_at
FROM last_pause l, patrols p
WHERE m.patrol_id = l.patrol_id AND m.user_id_snapshot = l.actor_user_id AND p.id = m.patrol_id
  AND p.activity_status IN ('IN_PROGRESS', 'RETURNING');

-- 3) 시니어 이벤트의 배정 확인: 받은 assignmentRef(배정 행 ID, NULL 허용)와 서버가 발생 시각으로 고른 참여 구간
ALTER TABLE patrol_events
    ADD COLUMN assignment_ref   uuid,
    ADD COLUMN participation_id uuid REFERENCES patrol_participations (id);

WITH resolved AS (
    SELECT DISTINCT ON (e.id) e.id AS event_id, pp.id AS participation_id
    FROM patrol_events e
    JOIN patrol_members m ON m.patrol_id = e.patrol_id AND m.user_id_snapshot = e.actor_user_id
    JOIN patrol_participations pp ON pp.patrol_id = m.patrol_id AND pp.participant_id = m.participant_id
    ORDER BY e.id, pp.created_at DESC
)
UPDATE patrol_events e SET participation_id = r.participation_id FROM resolved r WHERE e.id = r.event_id;

-- 4) 건너뛰기의 기준 이벤트: 구도를 SKIPPED로 만든 건너뛰기(D-39 사진 판정 기준), 사진 처리 중이라 보류한 건너뛰기
ALTER TABLE capture_slots ADD COLUMN skipped_by_event_id uuid REFERENCES patrol_events (id);
ALTER TABLE patrol_stops ADD COLUMN skip_pending_event_id uuid REFERENCES patrol_events (id);

WITH first_skip AS (
    SELECT DISTINCT ON (e.patrol_stop_id) e.patrol_stop_id, e.id
    FROM patrol_events e WHERE e.type = 'STOP_SKIPPED'
    ORDER BY e.patrol_stop_id, e.received_at, e.id
)
UPDATE capture_slots s SET skipped_by_event_id = f.id FROM first_skip f
WHERE s.patrol_stop_id = f.patrol_stop_id AND s.resolution = 'SKIPPED';

-- 04단계에서 건너뛰기 전 촬영 사진이 그 구도를 채우면(D-39) 결과가 ACCEPTED·REVIEW_REQUESTED로 바뀌어도 기준 이벤트 연결은 남긴다.
ALTER TABLE capture_slots
    ADD CONSTRAINT capture_slots_skipped_by_event CHECK (skipped_by_event_id IS NULL OR resolution IN ('SKIPPED', 'ACCEPTED', 'REVIEW_REQUESTED'));
