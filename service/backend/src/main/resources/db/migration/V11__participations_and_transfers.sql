-- 03단계 보완(2026-10-12): 활동 중 조 이동(D-34)과 참여 이력 보존(D-36). 근거: docs/03단계_API_DB_설계안.md 4절.
-- 배정 행 키 UNIQUE(patrol_id, participant_id)는 유지한다(같은 조로 돌아오면 행을 다시 활성화). 참여 구간은 별도 테이블에 쌓는다.

-- 1) 좌석: 결석·복귀 확인 조원도 배정 행을 가지므로 행 수 기준 1~3 제한을 풀고, 정원은 현장 조원 기준으로 이동 때 검사한다.
--    배정 중 좌석 중복 금지(ux_patrol_members_active_seat)는 그대로 둔다.
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'patrol_members'::regclass AND contype = 'c'
               AND (pg_get_constraintdef(oid) LIKE '%seat_no%' OR pg_get_constraintdef(oid) LIKE '%start_source%ADMIN_CORRECTION%')
    LOOP
        EXECUTE format('ALTER TABLE patrol_members DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;

ALTER TABLE patrol_members
    ADD CONSTRAINT patrol_members_seat_no_check CHECK (seat_no >= 1),
    -- CARRIED: 다른 조에서 기록한 개인 시작을 이동하면서 이어받은 값(D-36). 이 조의 출발 시각(departed_at) 계산에 넣지 않는다.
    ADD CONSTRAINT patrol_members_start_source_check CHECK (start_source IN ('SELF', 'ADMIN_CORRECTION', 'CARRIED'));

-- 2) 이동 이력: 이전·새 조, 시각, 처리 담당자, 사유(D-36)
CREATE TABLE patrol_member_transfers (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id     uuid         NOT NULL REFERENCES programs (id),
    participant_id uuid         NOT NULL,
    from_patrol_id uuid         NOT NULL,
    to_patrol_id   uuid         NOT NULL,
    transferred_at timestamptz  NOT NULL,
    transferred_by uuid         NOT NULL REFERENCES users (id),
    reason         varchar(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    from_version   bigint       NOT NULL,
    to_version     bigint       NOT NULL,
    FOREIGN KEY (program_id, from_patrol_id) REFERENCES patrols (program_id, id),
    FOREIGN KEY (program_id, to_patrol_id) REFERENCES patrols (program_id, id),
    FOREIGN KEY (from_patrol_id, participant_id) REFERENCES patrol_members (patrol_id, participant_id),
    FOREIGN KEY (to_patrol_id, participant_id) REFERENCES patrol_members (patrol_id, participant_id),
    CHECK (from_patrol_id <> to_patrol_id)
);
CREATE INDEX ix_patrol_member_transfers_participant ON patrol_member_transfers (participant_id, transferred_at);

-- 3) 참여 구간: 배정 중인 행마다 열린 구간 하나. 최초 배정(joined_at NULL)은 조 시작부터다.
--    접수 판정의 근거는 열린 구간과 TRANSFERRED 구간뿐이다. 재편성·취소로 빠진 구간은 이탈 시각을 지어내지 않는다(left_at NULL 허용).
CREATE TABLE patrol_participations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id      uuid        NOT NULL REFERENCES programs (id),
    patrol_id       uuid        NOT NULL,
    participant_id  uuid        NOT NULL,
    joined_at       timestamptz,
    left_at         timestamptz,
    leave_reason    varchar(20) CHECK (leave_reason IN ('TRANSFERRED', 'REASSIGNED', 'PATROL_CANCELLED')),
    transfer_in_id  uuid REFERENCES patrol_member_transfers (id),
    transfer_out_id uuid REFERENCES patrol_member_transfers (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (program_id, patrol_id) REFERENCES patrols (program_id, id),
    FOREIGN KEY (patrol_id, participant_id) REFERENCES patrol_members (patrol_id, participant_id),
    CHECK ((transfer_in_id IS NULL) = (joined_at IS NULL)),
    CHECK ((leave_reason IS NOT DISTINCT FROM 'TRANSFERRED') = (transfer_out_id IS NOT NULL)),
    CHECK (leave_reason IS DISTINCT FROM 'TRANSFERRED' OR left_at IS NOT NULL),
    CHECK (leave_reason IS NOT NULL OR left_at IS NULL),
    CHECK (left_at IS NULL OR joined_at IS NULL OR joined_at <= left_at)
);
CREATE UNIQUE INDEX ux_patrol_participations_open ON patrol_participations (patrol_id, participant_id) WHERE leave_reason IS NULL;
CREATE INDEX ix_patrol_participations_member ON patrol_participations (patrol_id, participant_id, created_at);

-- 기존 배정 행 백필: 배정 중이면 열린 구간, 아니면 취소 조는 PATROL_CANCELLED, 그 밖은 REASSIGNED(이탈 시각 없음)
INSERT INTO patrol_participations (program_id, patrol_id, participant_id, leave_reason)
SELECT m.program_id, m.patrol_id, m.participant_id,
       CASE WHEN m.assignment_active THEN NULL WHEN p.activity_status = 'CANCELLED' THEN 'PATROL_CANCELLED' ELSE 'REASSIGNED' END
FROM patrol_members m JOIN patrols p ON p.id = m.patrol_id;

-- 4) 배정 중 ⇔ 열린 구간: 배정 행이 활성화·생성되면 구간을 열고, 비활성화되면 닫는다.
--    이동은 서비스가 같은 트랜잭션에서 구간의 이동 정보(TRANSFERRED·시각·이동 ID)를 채운다.
CREATE FUNCTION patrol_members_participation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.assignment_active AND (TG_OP = 'INSERT' OR NOT OLD.assignment_active) THEN
        INSERT INTO patrol_participations (program_id, patrol_id, participant_id) VALUES (NEW.program_id, NEW.patrol_id, NEW.participant_id);
    ELSIF TG_OP = 'UPDATE' AND OLD.assignment_active AND NOT NEW.assignment_active THEN
        UPDATE patrol_participations
        SET leave_reason = CASE WHEN (SELECT activity_status FROM patrols WHERE id = NEW.patrol_id) = 'CANCELLED'
                                THEN 'PATROL_CANCELLED' ELSE 'REASSIGNED' END
        WHERE patrol_id = NEW.patrol_id AND participant_id = NEW.participant_id AND leave_reason IS NULL;
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER patrol_members_participation AFTER INSERT OR UPDATE OF assignment_active ON patrol_members
    FOR EACH ROW EXECUTE FUNCTION patrol_members_participation();

-- 5) 정원 이중 검증: 이동이 기록된 시점에 새 조의 현장 조원(배정 중·결석 아님·복귀 확인 전)은 3명 이하(D-34).
--    출결 정정으로 나중에 현장 조원이 늘어나는 것은 막지 않는다(D-37 정정 허용).
CREATE FUNCTION patrol_member_transfers_capacity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT count(*) FROM patrol_members WHERE patrol_id = NEW.to_patrol_id AND assignment_active AND attendance <> 'ABSENT'
            AND return_confirmed_at IS NULL) > 3 THEN
        RAISE EXCEPTION '조당 현장 조원은 3명을 넘을 수 없습니다(patrol %)', NEW.to_patrol_id USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER patrol_member_transfers_capacity AFTER INSERT ON patrol_member_transfers
    FOR EACH ROW EXECUTE FUNCTION patrol_member_transfers_capacity();
