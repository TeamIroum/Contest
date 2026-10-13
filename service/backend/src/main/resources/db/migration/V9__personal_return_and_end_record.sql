-- 03단계 보완(2026-10-12): 개인별 복귀(D-33)·조 종료 기록(D-36·D-37). 근거: docs/03단계_API_DB_설계안.md 2·3·8절.
-- 적용된 V1~V8은 고치지 않는다. 순서: 제약 해제 → 컬럼·테이블 추가 → 기존 데이터 이전 → 새 제약 → 트리거.
-- 이전 조 단위 복귀 컬럼(patrols.return_reason 등)은 지우지 않고 원본으로 남기며 새 코드는 쓰지 않는다.

-- 0) 이전 과정에서 상태·장소를 바꾼 행의 기록
CREATE TABLE data_migration_changes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    migration   varchar(20)  NOT NULL,
    rule        varchar(60)  NOT NULL,
    table_name  varchar(60)  NOT NULL,
    row_id      uuid         NOT NULL,
    before_json jsonb        NOT NULL,
    after_json  jsonb        NOT NULL,
    recorded_at timestamptz  NOT NULL DEFAULT now()
);

-- 1) 제약 해제: activity_status를 포함한 patrols CHECK 전부(값 목록, 취소·종료 쌍, 조 단위 구분 연동, V8 두 제약).
--    V3 인라인 CHECK는 이름이 자동 생성이므로 정의문으로 찾는다. 필요한 것은 4)에서 새 이름으로 다시 건다.
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'patrols'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%activity_status%'
    LOOP
        EXECUTE format('ALTER TABLE patrols DROP CONSTRAINT %I', r.conname);
    END LOOP;
    FOR r IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'patrol_stops'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%AUTO_ABORT%'
    LOOP
        EXECUTE format('ALTER TABLE patrol_stops DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;

-- 2) 개인별 복귀 요청·확인 구분(D-33)
ALTER TABLE patrol_members
    ADD COLUMN return_reason              varchar(10) CHECK (return_reason IN ('NORMAL', 'ABORT')),
    ADD COLUMN abort_reason               varchar(10) CHECK (abort_reason IN ('HEALTH', 'DEVICE', 'OTHER')),
    ADD COLUMN requested_return_reason    varchar(10) CHECK (requested_return_reason IN ('NORMAL', 'ABORT')),
    ADD COLUMN requested_abort_reason     varchar(10) CHECK (requested_abort_reason IN ('HEALTH', 'DEVICE', 'OTHER')),
    ADD COLUMN return_requested_at        timestamptz,
    ADD COLUMN return_request_received_at timestamptz;

ALTER TABLE patrol_stops
    ADD CONSTRAINT patrol_stops_not_visited_reason_check CHECK (not_visited_reason IN ('AUTO_ABORT', 'TEAM_ENDED'));

-- 종료 당시 기록(3.3절). 한 번 쓰면 바뀌지 않는다. 사후 보완은 조원 행의 현재 집계로 따로 보인다.
CREATE TABLE patrol_end_records (
    patrol_id              uuid PRIMARY KEY,
    program_id             uuid        NOT NULL,
    ended_at               timestamptz NOT NULL,
    end_reason             varchar(30) NOT NULL CHECK (end_reason IN ('ALL_RETURNED', 'ALL_TRANSFERRED', 'RETURNED_AND_TRANSFERRED')),
    trigger_type           varchar(30) NOT NULL CHECK (trigger_type IN ('LAST_RETURN_CONFIRMED', 'LAST_MEMBER_TRANSFERRED', 'ABSENT_CONFIRMED')),
    trigger_participant_id uuid,
    actor_user_id          uuid REFERENCES users (id),
    normal_returned        integer     NOT NULL CHECK (normal_returned >= 0),
    abort_returned         integer     NOT NULL CHECK (abort_returned >= 0),
    transferred_out        integer     NOT NULL CHECK (transferred_out >= 0),
    absent                 integer     NOT NULL CHECK (absent >= 0),
    record_source          varchar(30) NOT NULL CHECK (record_source IN ('LIVE', 'MIGRATED_RECONSTRUCTED', 'MIGRATED_UNVERIFIED')),
    created_at             timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (program_id, patrol_id) REFERENCES patrols (program_id, id),
    CHECK (record_source <> 'LIVE' OR (actor_user_id IS NOT NULL AND trigger_participant_id IS NOT NULL)),
    CHECK (normal_returned + abort_returned + transferred_out >= 1)
);

-- 3) 기존 데이터 이전(유실 방지)
-- 3-1) 확인된 조원에게만 조 단위 확정 구분을 복사한다. 시각 정정만 있고 확인이 없는 조원은 비운다.
UPDATE patrol_members m SET return_reason = p.return_reason, abort_reason = p.abort_reason
FROM patrols p
WHERE p.id = m.patrol_id AND p.return_reason_source = 'CONFIRMED' AND m.return_confirmed_at IS NOT NULL;

-- 3-2) 앱 복귀 요청은 보낸 사람(actor)의 행에 가장 이른 수신 이벤트 값으로 채운다(조 단위 REQUESTED 값은 보낸 사람을 모른다).
WITH first_request AS (
    SELECT DISTINCT ON (e.patrol_id, e.actor_user_id) e.patrol_id, e.actor_user_id, e.reason_code, e.occurred_at, e.received_at
    FROM patrol_events e WHERE e.type = 'RETURN_REQUESTED'
    ORDER BY e.patrol_id, e.actor_user_id, e.received_at, e.id
)
UPDATE patrol_members m
SET requested_return_reason = split_part(f.reason_code, ':', 1),
    requested_abort_reason = NULLIF(split_part(f.reason_code, ':', 2), ''),
    return_requested_at = f.occurred_at,
    return_request_received_at = f.received_at
FROM first_request f
WHERE m.patrol_id = f.patrol_id AND m.user_id_snapshot = f.actor_user_id;

-- 3-3) 종료 조: 종료 기록을 종료 시점 기준으로 만든다. 종료 뒤 바뀐 출결은 감사 이력의 변경 전 값으로 되돌려 센다.
--      감사 이력이 없거나 같은 참여자의 다른 배정과 구분할 수 없으면 MIGRATED_UNVERIFIED.
DO $$
DECLARE
    p record;
    m record;
    at_end varchar(10);
    unverified boolean;
    normal_count integer;
    abort_count integer;
    absent_count integer;
    confirm_at timestamptz;
    confirm_pid uuid;
    confirm_by uuid;
    absent_at timestamptz;
    absent_pid uuid;
    absent_by uuid;
    by_absent boolean;
BEGIN
    FOR p IN SELECT id, program_id, finished_at FROM patrols WHERE activity_status IN ('COMPLETED', 'ABORTED') LOOP
        unverified := false;
        absent_count := 0;
        absent_at := NULL;
        absent_pid := NULL;
        absent_by := NULL;
        FOR m IN SELECT participant_id, attendance, attendance_checked_at, attendance_checked_by FROM patrol_members
                 WHERE patrol_id = p.id AND assignment_active LOOP
            at_end := m.attendance;
            IF m.attendance_checked_at IS NOT NULL AND m.attendance_checked_at > p.finished_at THEN
                IF (SELECT count(*) FROM patrol_members WHERE participant_id = m.participant_id) > 1 THEN
                    unverified := true;
                ELSE
                    at_end := NULL;
                    SELECT a.before_json ->> 'attendance' INTO at_end FROM audit_events a
                    WHERE a.program_id = p.program_id AND a.action = 'ATTENDANCE_CONFIRMED' AND a.resource_type = 'PATROL_MEMBER'
                      AND a.resource_id = m.participant_id AND a.created_at > p.finished_at
                    ORDER BY a.created_at, a.id LIMIT 1;
                    IF at_end IS NULL THEN
                        unverified := true;
                        at_end := m.attendance;
                    END IF;
                END IF;
            END IF;
            IF at_end = 'ABSENT' THEN
                absent_count := absent_count + 1;
                IF m.attendance_checked_at <= p.finished_at AND (absent_at IS NULL OR m.attendance_checked_at > absent_at) THEN
                    absent_at := m.attendance_checked_at;
                    absent_pid := m.participant_id;
                    absent_by := m.attendance_checked_by;
                END IF;
            END IF;
        END LOOP;
        SELECT count(*) FILTER (WHERE return_reason = 'NORMAL'), count(*) FILTER (WHERE return_reason = 'ABORT')
        INTO normal_count, abort_count
        FROM patrol_members WHERE patrol_id = p.id AND assignment_active AND return_confirmed_at <= p.finished_at;
        confirm_at := NULL;
        confirm_pid := NULL;
        confirm_by := NULL;
        SELECT return_confirmed_at, participant_id, return_confirmed_by INTO confirm_at, confirm_pid, confirm_by FROM patrol_members
        WHERE patrol_id = p.id AND assignment_active AND return_confirmed_at <= p.finished_at
        ORDER BY return_confirmed_at DESC, participant_id LIMIT 1;
        by_absent := absent_at IS NOT NULL AND (confirm_at IS NULL OR absent_at > confirm_at);
        INSERT INTO patrol_end_records (patrol_id, program_id, ended_at, end_reason, trigger_type, trigger_participant_id, actor_user_id,
            normal_returned, abort_returned, transferred_out, absent, record_source)
        VALUES (p.id, p.program_id, p.finished_at, 'ALL_RETURNED',
            CASE WHEN by_absent THEN 'ABSENT_CONFIRMED' ELSE 'LAST_RETURN_CONFIRMED' END,
            CASE WHEN by_absent THEN absent_pid ELSE confirm_pid END,
            CASE WHEN by_absent THEN absent_by ELSE confirm_by END,
            normal_count, abort_count, 0, absent_count,
            CASE WHEN unverified THEN 'MIGRATED_UNVERIFIED' ELSE 'MIGRATED_RECONSTRUCTED' END);
    END LOOP;
END $$;

-- 3-4) 종료 조의 남은 PENDING 장소 → NOT_VISITED(TEAM_ENDED). 늦은 적법 사진 보완 대상이다(D-27).
WITH changed AS (
    UPDATE patrol_stops s SET visit_status = 'NOT_VISITED', not_visited_reason = 'TEAM_ENDED'
    FROM patrols p
    WHERE p.id = s.patrol_id AND p.activity_status IN ('COMPLETED', 'ABORTED') AND s.visit_status = 'PENDING'
    RETURNING s.id
)
INSERT INTO data_migration_changes (migration, rule, table_name, row_id, before_json, after_json)
SELECT 'V9', 'ENDED_PENDING_STOP', 'patrol_stops', id, '{"visitStatus":"PENDING","notVisitedReason":null}',
       '{"visitStatus":"NOT_VISITED","notVisitedReason":"TEAM_ENDED"}' FROM changed;

-- 3-5) 진행 중 조의 AUTO_ABORT 장소: 이전 규칙은 한 명의 중도 복귀만으로 남은 장소를 막았다.
--      현장 조원(배정 중·ABSENT 아님·복귀 확인 전)이 남은 조만 PENDING으로 돌린다. 자동 추정만 고치며 사진·건너뛰기는 건드리지 않는다.
WITH changed AS (
    UPDATE patrol_stops s SET visit_status = 'PENDING', not_visited_reason = NULL
    FROM patrols p
    WHERE p.id = s.patrol_id AND p.activity_status IN ('ASSIGNED', 'IN_PROGRESS', 'RETURNING') AND s.not_visited_reason = 'AUTO_ABORT'
      AND EXISTS (SELECT 1 FROM patrol_members m WHERE m.patrol_id = p.id AND m.assignment_active AND m.attendance <> 'ABSENT'
                  AND m.return_confirmed_at IS NULL)
    RETURNING s.id
)
INSERT INTO data_migration_changes (migration, rule, table_name, row_id, before_json, after_json)
SELECT 'V9', 'ACTIVE_AUTO_ABORT_STOP_REOPENED', 'patrol_stops', id, '{"visitStatus":"NOT_VISITED","notVisitedReason":"AUTO_ABORT"}',
       '{"visitStatus":"PENDING","notVisitedReason":null}' FROM changed;

-- 3-6) 종료 상태 통합: COMPLETED/ABORTED → ENDED(finished_at 유지). 바꾸기 전 값을 먼저 기록한다.
INSERT INTO data_migration_changes (migration, rule, table_name, row_id, before_json, after_json)
SELECT 'V9', 'TERMINAL_TO_ENDED', 'patrols', id, jsonb_build_object('activityStatus', activity_status), '{"activityStatus":"ENDED"}'
FROM patrols WHERE activity_status IN ('COMPLETED', 'ABORTED');

UPDATE patrols SET activity_status = 'ENDED' WHERE activity_status IN ('COMPLETED', 'ABORTED');

-- 4) 새 제약
ALTER TABLE patrols
    ADD CONSTRAINT patrols_activity_status_check CHECK (activity_status IN ('ASSIGNED', 'IN_PROGRESS', 'RETURNING', 'ENDED', 'CANCELLED')),
    ADD CONSTRAINT patrols_cancelled_record_check CHECK ((activity_status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    ADD CONSTRAINT patrols_ended_finished_check CHECK ((activity_status = 'ENDED') = (finished_at IS NOT NULL));

ALTER TABLE patrol_members
    ADD CONSTRAINT patrol_members_return_reason_confirmed CHECK ((return_reason IS NULL) = (return_confirmed_at IS NULL)),
    ADD CONSTRAINT patrol_members_abort_reason_pair CHECK (abort_reason IS NULL OR return_reason IS NOT DISTINCT FROM 'ABORT'),
    ADD CONSTRAINT patrol_members_abort_requires_reason CHECK (return_reason IS DISTINCT FROM 'ABORT' OR abort_reason IS NOT NULL),
    ADD CONSTRAINT patrol_members_return_request_pair CHECK (
        (requested_return_reason IS NULL) = (return_requested_at IS NULL)
        AND (return_requested_at IS NULL) = (return_request_received_at IS NULL)),
    ADD CONSTRAINT patrol_members_requested_abort_pair CHECK (
        requested_abort_reason IS NULL OR requested_return_reason IS NOT DISTINCT FROM 'ABORT'),
    ADD CONSTRAINT patrol_members_requested_abort_requires_reason CHECK (
        requested_return_reason IS DISTINCT FROM 'ABORT' OR requested_abort_reason IS NOT NULL);

-- 5) 트리거: 종료 기록은 변경 불가, ENDED ⇔ 종료 기록 존재(트랜잭션 끝에서 검사).
CREATE FUNCTION patrol_end_records_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '종료 기록은 수정·삭제할 수 없습니다(patrol %)', OLD.patrol_id USING ERRCODE = '55000';
END $$;

CREATE TRIGGER patrol_end_records_immutable BEFORE UPDATE OR DELETE ON patrol_end_records
    FOR EACH ROW EXECUTE FUNCTION patrol_end_records_immutable();

CREATE FUNCTION patrol_end_record_check(pid uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    ended boolean;
    recorded boolean;
BEGIN
    SELECT activity_status = 'ENDED' INTO ended FROM patrols WHERE id = pid;
    SELECT EXISTS (SELECT 1 FROM patrol_end_records WHERE patrol_id = pid) INTO recorded;
    IF ended IS DISTINCT FROM recorded THEN
        RAISE EXCEPTION 'ENDED 상태와 종료 기록이 맞지 않습니다(patrol %)', pid USING ERRCODE = '23514';
    END IF;
END $$;

CREATE FUNCTION patrols_end_record_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM patrol_end_record_check(NEW.id);
    RETURN NULL;
END $$;

CREATE FUNCTION patrol_end_records_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM patrol_end_record_check(NEW.patrol_id);
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER patrols_end_record_consistency AFTER INSERT OR UPDATE OF activity_status ON patrols
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION patrols_end_record_consistency();

CREATE CONSTRAINT TRIGGER patrol_end_records_consistency AFTER INSERT ON patrol_end_records
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION patrol_end_records_consistency();
