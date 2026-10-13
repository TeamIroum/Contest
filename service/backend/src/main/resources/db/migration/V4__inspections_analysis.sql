-- DB 설계 v1.5 7절: 사진 시도, AI 분석 작업·결과 스냅샷, 사람 분류 정정

CREATE TABLE inspections (
    id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id                 uuid         NOT NULL REFERENCES programs (id),
    capture_slot_id            uuid         NOT NULL,
    submitted_by               uuid         NOT NULL REFERENCES users (id),
    client_submission_id       uuid         NOT NULL,
    attempt_id                 uuid         NOT NULL,
    request_hash               bytea        NOT NULL,
    submission_sha256          bytea        NOT NULL CHECK (octet_length(submission_sha256) = 32),
    attempt_no                 integer      NOT NULL CHECK (attempt_no >= 1),
    media_id                   uuid,
    approved_media_id          uuid,
    captured_at                timestamptz  NOT NULL,
    submitted_at               timestamptz  NOT NULL DEFAULT now(),
    received_at                timestamptz,
    receipt_status             varchar(20)  NOT NULL DEFAULT 'RECEIVING'
        CHECK (receipt_status IN ('RECEIVING', 'RECEIVED', 'NO_MEDIA', 'RETRY_REQUIRED')),
    ingestion_lease_token      uuid,
    ingestion_lease_until      timestamptz,
    masking_version            varchar(50)  NOT NULL,
    device_masked              boolean      NOT NULL CHECK (device_masked),
    device_mask_report_json    jsonb        NOT NULL CHECK (jsonb_typeof(device_mask_report_json) = 'object'),
    capture_status             varchar(20)  NOT NULL DEFAULT 'PROCESSING'
        CHECK (capture_status IN ('PROCESSING', 'ACCEPTED', 'RETAKE_REQUIRED', 'TECHNICAL_FAILED', 'REVIEW_REQUESTED')),
    retake_reason              varchar(20)  CHECK (retake_reason IN ('BLUR', 'DARK', 'OUT_OF_FRAME', 'NO_FACILITY')),
    quality_failure_applied_at timestamptz,
    classification_version     integer      NOT NULL DEFAULT 0 CHECK (classification_version >= 0),
    latitude                   numeric(9, 6) CHECK (latitude BETWEEN -90 AND 90),
    longitude                  numeric(10, 6) CHECK (longitude BETWEEN -180 AND 180),
    location_accuracy_meters   numeric(8, 2) CHECK (location_accuracy_meters >= 0),
    created_at                 timestamptz  NOT NULL DEFAULT now(),
    updated_at                 timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    UNIQUE (id, capture_slot_id),
    UNIQUE (submitted_by, client_submission_id),
    UNIQUE (submitted_by, attempt_id),
    UNIQUE (capture_slot_id, attempt_no),
    FOREIGN KEY (program_id, capture_slot_id) REFERENCES capture_slots (program_id, id),
    FOREIGN KEY (program_id, media_id) REFERENCES media (program_id, id),
    FOREIGN KEY (program_id, approved_media_id) REFERENCES media (program_id, id),
    -- RECEIVED만 영속 사진과 수신 시각을 갖는다. 개인정보 보류 뒤에는 approved_media_id만 해제된다(DB 설계 7절).
    CHECK (receipt_status <> 'RECEIVED' OR (media_id IS NOT NULL AND received_at IS NOT NULL)),
    CHECK (receipt_status = 'RECEIVED' OR (media_id IS NULL AND approved_media_id IS NULL AND received_at IS NULL)),
    CHECK (approved_media_id IS NULL OR media_id IS NOT NULL),
    CHECK (capture_status <> 'ACCEPTED' OR approved_media_id IS NOT NULL),
    CHECK ((ingestion_lease_token IS NULL) = (ingestion_lease_until IS NULL)),
    CHECK (retake_reason IS NULL OR capture_status IN ('RETAKE_REQUIRED', 'REVIEW_REQUESTED')),
    CHECK ((retake_reason IS NOT NULL) = (quality_failure_applied_at IS NOT NULL)),
    CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CHECK (location_accuracy_meters IS NULL OR latitude IS NOT NULL)
);
-- 구도당 ACCEPTED 사진은 하나(DB 설계 7.1절)
CREATE UNIQUE INDEX ux_inspections_accepted_per_slot ON inspections (capture_slot_id) WHERE capture_status = 'ACCEPTED';
CREATE INDEX ix_inspections_slot_attempt ON inspections (capture_slot_id, attempt_no DESC);
CREATE INDEX ix_inspections_ingestion ON inspections (receipt_status, ingestion_lease_until)
    WHERE receipt_status IN ('RECEIVING', 'RETRY_REQUIRED');

-- 구도의 채택·처리 중 포인터는 같은 구도의 시도만 가리킨다.
ALTER TABLE capture_slots
    ADD CONSTRAINT fk_capture_slots_accepted FOREIGN KEY (accepted_inspection_id, id) REFERENCES inspections (id, capture_slot_id),
    ADD CONSTRAINT fk_capture_slots_active FOREIGN KEY (active_inspection_id, id) REFERENCES inspections (id, capture_slot_id);

CREATE TABLE analysis_jobs (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id        uuid         NOT NULL REFERENCES programs (id),
    inspection_id     uuid,
    verification_id   uuid,
    media_id          uuid,
    type              varchar(20)  NOT NULL CHECK (type IN ('ANALYZE', 'QUALITY', 'DEFECT', 'COMPARISON', 'PRIVACY')),
    status            varchar(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    retry_owner       varchar(10)  NOT NULL DEFAULT 'BACKEND' CHECK (retry_owner IN ('BACKEND', 'AI')),
    provider_photo_id varchar(80),
    input_hash        bytea,
    attempt_count     integer      NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_run_at       timestamptz,
    locked_until      timestamptz,
    lease_token       uuid,
    model_version     varchar(100),
    input_version     integer      NOT NULL DEFAULT 1 CHECK (input_version >= 1),
    error_code        varchar(50),
    started_at        timestamptz,
    finished_at       timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    UNIQUE (provider_photo_id),
    FOREIGN KEY (program_id, inspection_id) REFERENCES inspections (program_id, id),
    FOREIGN KEY (program_id, media_id) REFERENCES media (program_id, id),
    -- 대상은 정확히 하나이며 작업 종류와 맞아야 한다.
    CHECK (num_nonnulls(inspection_id, verification_id, media_id) = 1),
    CHECK ((type IN ('ANALYZE', 'QUALITY', 'DEFECT')) = (inspection_id IS NOT NULL)),
    CHECK ((type = 'COMPARISON') = (verification_id IS NOT NULL)),
    CHECK ((type = 'PRIVACY') = (media_id IS NOT NULL)),
    CHECK ((locked_until IS NULL) = (lease_token IS NULL)),
    CHECK ((provider_photo_id IS NULL) = (input_hash IS NULL))
);
CREATE UNIQUE INDEX ux_analysis_jobs_inspection ON analysis_jobs (inspection_id, type, input_version) WHERE inspection_id IS NOT NULL;
CREATE UNIQUE INDEX ux_analysis_jobs_verification ON analysis_jobs (verification_id, type, input_version) WHERE verification_id IS NOT NULL;
CREATE UNIQUE INDEX ux_analysis_jobs_media ON analysis_jobs (media_id, type, input_version) WHERE media_id IS NOT NULL;
CREATE INDEX ix_analysis_jobs_runnable ON analysis_jobs (status, next_run_at) WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX ix_analysis_jobs_lease ON analysis_jobs (locked_until) WHERE status = 'PROCESSING';
-- verification_id FK는 verifications 생성 후 V6에서 추가한다.

CREATE TABLE analysis_results (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id              uuid         NOT NULL REFERENCES programs (id),
    analysis_job_id         uuid         NOT NULL,
    snapshot_no             integer      NOT NULL CHECK (snapshot_no >= 1),
    provider_result_version integer,
    result_hash             bytea        NOT NULL,
    -- AI 원문은 내부 제한 저장. 사진 URL·비밀 값은 제거하고 사용자 응답에는 허용 필드만 변환한다.
    result_json             jsonb        NOT NULL,
    model_version           varchar(100),
    prompt_version          varchar(100),
    contract_version        varchar(20),
    observed_at             timestamptz  NOT NULL DEFAULT now(),
    image_media_id          uuid,
    UNIQUE (program_id, id),
    UNIQUE (analysis_job_id, snapshot_no),
    FOREIGN KEY (program_id, analysis_job_id) REFERENCES analysis_jobs (program_id, id),
    FOREIGN KEY (program_id, image_media_id) REFERENCES media (program_id, id)
);

CREATE TABLE classification_corrections (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id             uuid         NOT NULL REFERENCES programs (id),
    inspection_id          uuid         NOT NULL,
    analysis_result_id     uuid         NOT NULL,
    previous_facility_type varchar(30)  NOT NULL,
    previous_issue_types   jsonb        NOT NULL CHECK (jsonb_typeof(previous_issue_types) = 'array'),
    new_facility_type      varchar(30)  NOT NULL CHECK (new_facility_type IN ('SIDEWALK', 'DRAIN', 'BENCH', 'SAFETY_FENCE', 'EXERCISE_EQUIPMENT',
                                                                               'PARK_EQUIPMENT', 'SNOW_REMOVAL_BIN', 'SHADE_STRUCTURE')),
    new_issue_types        jsonb        NOT NULL CHECK (jsonb_typeof(new_issue_types) = 'array'),
    reason                 varchar(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    actor_user_id          uuid         NOT NULL REFERENCES users (id),
    classification_version integer      NOT NULL CHECK (classification_version >= 1),
    created_at             timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    UNIQUE (program_id, inspection_id, classification_version),
    FOREIGN KEY (program_id, inspection_id) REFERENCES inspections (program_id, id),
    FOREIGN KEY (program_id, analysis_result_id) REFERENCES analysis_results (program_id, id)
);
