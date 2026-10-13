-- 04단계(2026-10-13): 사진 접수·개인정보 보류 해제의 저장 구조. 근거: DB 설계 7절, 업무 API 6.3·9.3·15절,
-- 03단계 설계안 7절(촬영 당시 참여 구간), docs/04단계_계약_공백과_설계선택.md.

-- 1) 사진 시도의 촬영 당시 배정: 서버가 촬영 시각으로 고른 참여 구간과 받은 assignmentRef(선택, 멱등 해시 제외)
ALTER TABLE inspections
    ADD COLUMN participation_id uuid REFERENCES patrol_participations (id),
    ADD COLUMN assignment_ref   uuid;

-- 2) D-39: 건너뛴 구도에 건너뛰기 전 촬영 사진이 처리되는 동안은 구도를 OPEN으로 두고 처리 중 포인터를 잡는다.
--    기준 건너뛰기 연결(skipped_by_event_id)은 처리 중에도 남긴다. 처리 결과가 채택이면 ACCEPTED, 검토 인계면 REVIEW_REQUESTED,
--    접수 실패(사진 미확보)면 다시 SKIPPED로 돌아간다.
ALTER TABLE capture_slots DROP CONSTRAINT capture_slots_skipped_by_event;
ALTER TABLE capture_slots
    ADD CONSTRAINT capture_slots_skipped_by_event CHECK (
        skipped_by_event_id IS NULL OR resolution IN ('SKIPPED', 'ACCEPTED', 'REVIEW_REQUESTED')
        OR (resolution = 'OPEN' AND active_inspection_id IS NOT NULL));

-- 3) 재가림으로 대체된 사진 사본: 지우지 않고 대체한 새 행을 가리킨다. 대체된 사본은 조회하지 않는다(업무 API 15.3절).
ALTER TABLE media
    ADD COLUMN superseded_by_media_id uuid,
    ADD CONSTRAINT media_superseded_by_fkey FOREIGN KEY (program_id, superseded_by_media_id) REFERENCES media (program_id, id),
    ADD CONSTRAINT media_not_superseded_by_itself CHECK (superseded_by_media_id IS DISTINCT FROM id);
CREATE INDEX ix_media_provider_photo ON media (provider_photo_id, provider_image_revision) WHERE provider_photo_id IS NOT NULL;

-- 4) 개인정보 검토 결정과 보류 해제(재승인) 처리 상태(D-28, DB 설계 7절)
CREATE TABLE privacy_reviews (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id                uuid         NOT NULL REFERENCES programs (id),
    media_id                  uuid         NOT NULL,
    decided_by                uuid         NOT NULL REFERENCES users (id),
    ok                        boolean      NOT NULL,
    reason                    varchar(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    -- 결정 시점의 사진 기록 version, 사진 내용 버전(mediaVersion), AI 사진 버전
    media_record_version      bigint       NOT NULL CHECK (media_record_version >= 0),
    decided_media_version     integer      NOT NULL CHECK (decided_media_version >= 1),
    decided_provider_revision integer      CHECK (decided_provider_revision >= 1),
    -- AI 가림 점검 기록 전송 상태(ok=false 보류 전달, ok=true 해제 전달 모두)
    provider_sync_status      varchar(10)  NOT NULL CHECK (provider_sync_status IN ('PENDING', 'SENT', 'FAILED')),
    release_status            varchar(20)  CHECK (release_status IN ('PENDING', 'FAILED', 'COMPLETED', 'INVALIDATED')),
    attempt_count             integer      NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    cycle_attempt_count       integer      NOT NULL DEFAULT 0 CHECK (cycle_attempt_count >= 0),
    next_attempt_at           timestamptz,
    -- AI 오류 code(내부 전용, 응답에 넣지 않음)
    last_error_code           varchar(50),
    failure_reason            varchar(30)  CHECK (failure_reason IN ('RETRY_EXHAUSTED', 'PROVIDER_REJECTED')),
    invalid_reason            varchar(30)  CHECK (invalid_reason IN ('REMASKED', 'NEW_PRIVACY_HOLD')),
    completed_at              timestamptz,
    version                   bigint       NOT NULL DEFAULT 0,
    created_at                timestamptz  NOT NULL,
    UNIQUE (program_id, id),
    FOREIGN KEY (program_id, media_id) REFERENCES media (program_id, id),
    CHECK (release_status IS NULL OR ok),
    CHECK ((failure_reason IS NOT NULL) = (release_status IS NOT DISTINCT FROM 'FAILED')),
    CHECK ((invalid_reason IS NOT NULL) = (release_status IS NOT DISTINCT FROM 'INVALIDATED')),
    CHECK ((completed_at IS NOT NULL) = (release_status IS NOT DISTINCT FROM 'COMPLETED'))
);
-- 사진별 진행 중 재승인은 하나
CREATE UNIQUE INDEX ux_privacy_reviews_in_progress ON privacy_reviews (media_id) WHERE release_status IN ('PENDING', 'FAILED');
CREATE INDEX ix_privacy_reviews_media ON privacy_reviews (media_id, created_at DESC);
CREATE INDEX ix_privacy_reviews_due ON privacy_reviews (next_attempt_at) WHERE provider_sync_status = 'PENDING';
