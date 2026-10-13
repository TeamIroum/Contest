-- 리뷰 재현 보완(2026-10-12, ACT-17): 개인 복귀 요청을 참여 구간별로 분리한다(D-33·D-36, 설계안 4.3·4.7).
-- 배정 행(patrol_members)의 요청값은 지금 참여 구간의 요청만 뜻한다. 이동으로 닫힌 구간의 요청은 그 구간 행에 남기고(이력 보존),
-- 같은 조로 다시 합류하면 배정 행의 요청값은 비운 채 새 구간을 시작한다. 원본 이벤트(patrol_events RETURN_REQUESTED)는 그대로다.
ALTER TABLE patrol_participations
    ADD COLUMN requested_return_reason    varchar(10) CHECK (requested_return_reason IN ('NORMAL', 'ABORT')),
    ADD COLUMN requested_abort_reason     varchar(10) CHECK (requested_abort_reason IN ('HEALTH', 'DEVICE', 'OTHER')),
    ADD COLUMN return_requested_at        timestamptz,
    ADD COLUMN return_request_received_at timestamptz,
    ADD CONSTRAINT patrol_participations_return_request_pair CHECK (
        (requested_return_reason IS NULL) = (return_requested_at IS NULL)
        AND (return_requested_at IS NULL) = (return_request_received_at IS NULL)),
    ADD CONSTRAINT patrol_participations_requested_abort_pair CHECK (
        requested_abort_reason IS NULL OR requested_return_reason IS NOT DISTINCT FROM 'ABORT'),
    ADD CONSTRAINT patrol_participations_requested_abort_requires_reason CHECK (
        requested_return_reason IS DISTINCT FROM 'ABORT' OR requested_abort_reason IS NOT NULL);

-- 1) 이동으로 닫힌 구간: 배정 행의 요청이 그 구간 안에서 발생했으면 구간 행에 남긴다.
UPDATE patrol_participations pp
SET requested_return_reason = m.requested_return_reason, requested_abort_reason = m.requested_abort_reason,
    return_requested_at = m.return_requested_at, return_request_received_at = m.return_request_received_at
FROM patrol_members m
WHERE m.patrol_id = pp.patrol_id AND m.participant_id = pp.participant_id AND pp.leave_reason = 'TRANSFERRED'
  AND m.return_requested_at IS NOT NULL
  AND (pp.joined_at IS NULL OR pp.joined_at <= m.return_requested_at) AND m.return_requested_at < pp.left_at;

-- 2) 다시 합류한 배정 행에 이전 구간 요청이 남아 있으면 비운다(값은 1)의 구간 행과 이벤트에 남아 있음). 바꾼 행은 기록한다.
WITH stale AS (
    SELECT m.id, m.requested_return_reason, m.requested_abort_reason, m.return_requested_at
    FROM patrol_members m
    JOIN patrol_participations cur ON cur.patrol_id = m.patrol_id AND cur.participant_id = m.participant_id AND cur.leave_reason IS NULL
    WHERE m.assignment_active AND m.return_requested_at IS NOT NULL AND cur.joined_at IS NOT NULL AND m.return_requested_at < cur.joined_at
), logged AS (
    INSERT INTO data_migration_changes (migration, rule, table_name, row_id, before_json, after_json)
    SELECT 'V13', 'REJOINED_MEMBER_PAST_RETURN_REQUEST', 'patrol_members', id,
           jsonb_build_object('requestedReturnReason', requested_return_reason, 'requestedAbortReason', requested_abort_reason,
                              'returnRequestedAt', return_requested_at),
           '{"requestedReturnReason":null,"requestedAbortReason":null,"returnRequestedAt":null}'
    FROM stale
    RETURNING row_id
)
UPDATE patrol_members m
SET requested_return_reason = NULL, requested_abort_reason = NULL, return_requested_at = NULL, return_request_received_at = NULL
FROM logged WHERE m.id = logged.row_id;
