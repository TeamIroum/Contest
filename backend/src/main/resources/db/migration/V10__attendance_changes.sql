-- 03단계 보완(2026-10-12): 출결 변경 이력과 정정 사유(D-37). 근거: docs/03단계_API_DB_설계안.md 6절.
-- 담당자의 출결 저장마다 한 행을 남긴다. 확정 결석(ABSENT)을 출석(PRESENT)으로 바꾸는 정정은 출석 요청 유무와 무관하게 사유가 필요하다.
CREATE TABLE patrol_attendance_changes (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id        uuid         NOT NULL REFERENCES programs (id),
    patrol_id         uuid         NOT NULL,
    participant_id    uuid         NOT NULL,
    before_attendance varchar(10)  NOT NULL CHECK (before_attendance IN ('UNCHECKED', 'PRESENT', 'ABSENT')),
    after_attendance  varchar(10)  NOT NULL CHECK (after_attendance IN ('PRESENT', 'ABSENT')),
    correction_reason varchar(500) CHECK (correction_reason IS NULL OR length(btrim(correction_reason)) BETWEEN 1 AND 500),
    proxy_reason      varchar(500),
    actor_user_id     uuid         NOT NULL REFERENCES users (id),
    recorded_at       timestamptz  NOT NULL,
    FOREIGN KEY (program_id, patrol_id) REFERENCES patrols (program_id, id),
    FOREIGN KEY (patrol_id, participant_id) REFERENCES patrol_members (patrol_id, participant_id),
    CHECK (NOT (before_attendance = 'ABSENT' AND after_attendance = 'PRESENT') OR correction_reason IS NOT NULL)
);
CREATE INDEX ix_patrol_attendance_changes_member ON patrol_attendance_changes (patrol_id, participant_id, recorded_at);
