-- DB 설계 v1.5 5·7절: 승인 사진(media), 시설 대장, 코스·버전·장소·구도 템플릿

CREATE TABLE media (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id              uuid         NOT NULL REFERENCES programs (id),
    uploaded_by             uuid         REFERENCES users (id),
    bucket                  varchar(100) NOT NULL,
    object_key              varchar(500) NOT NULL,
    purpose                 varchar(20)  NOT NULL CHECK (purpose IN ('INSPECTION', 'REFERENCE', 'REPAIR_EVIDENCE')),
    privacy_status          varchar(20)  NOT NULL CHECK (privacy_status IN ('QUARANTINED', 'APPROVED', 'REJECTED')),
    source_media_id         uuid,
    content_type            varchar(30)  NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    size_bytes              bigint       NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    sha256                  bytea        NOT NULL CHECK (octet_length(sha256) = 32),
    provider_photo_id       varchar(80),
    provider_image_revision integer      CHECK (provider_image_revision >= 1),
    privacy_report_json     jsonb,
    -- D-19 보존 설계안. 기간·근거·기산일은 운영기관 확정 전 미정이므로 값 규칙을 두지 않는다.
    -- NULL은 무기한 보관 승인을 뜻하지 않는다.
    retention_policy_id     varchar(50),
    retain_until            timestamptz,
    preservation_hold_reason varchar(500),
    hold_until              timestamptz,
    disposal_status         varchar(20),
    disposed_at             timestamptz,
    deleted_at              timestamptz,
    version                 bigint       NOT NULL DEFAULT 0,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    UNIQUE (bucket, object_key),
    FOREIGN KEY (program_id, source_media_id) REFERENCES media (program_id, id),
    CHECK ((provider_photo_id IS NULL) = (provider_image_revision IS NULL))
);

CREATE TABLE facilities (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id             uuid          NOT NULL REFERENCES programs (id),
    facility_code          varchar(30)   NOT NULL,
    type                   varchar(30)   NOT NULL CHECK (type IN ('SIDEWALK', 'DRAIN', 'BENCH', 'SAFETY_FENCE', 'EXERCISE_EQUIPMENT',
                                                                   'PARK_EQUIPMENT', 'SNOW_REMOVAL_BIN', 'SHADE_STRUCTURE')),
    name                   varchar(100)  NOT NULL,
    latitude               numeric(9, 6) CHECK (latitude BETWEEN -90 AND 90),
    longitude              numeric(10, 6) CHECK (longitude BETWEEN -180 AND 180),
    area_label             varchar(100),
    managing_department_id uuid          NOT NULL,
    public_managed         boolean       NOT NULL,
    location_tags_json     jsonb         NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(location_tags_json) = 'array'),
    status                 varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version                bigint        NOT NULL DEFAULT 0,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    UNIQUE (program_id, facility_code),
    FOREIGN KEY (program_id, managing_department_id) REFERENCES organizations (program_id, id),
    CHECK ((latitude IS NULL) = (longitude IS NULL))
);
-- FACILITY_MANAGER 관할은 managing_department_id의 현재 값으로 판단한다(API 3절).
CREATE INDEX ix_facilities_department ON facilities (program_id, managing_department_id);

CREATE TABLE routes (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id      uuid         NOT NULL REFERENCES programs (id),
    organization_id uuid         NOT NULL,
    name            varchar(100) NOT NULL,
    status          varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    FOREIGN KEY (program_id, organization_id) REFERENCES organizations (program_id, id)
);

CREATE TABLE route_versions (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id        uuid        NOT NULL REFERENCES programs (id),
    route_id          uuid        NOT NULL,
    version_no        integer     NOT NULL CHECK (version_no >= 1),
    departure_base_id uuid        NOT NULL,
    return_base_id    uuid        NOT NULL,
    published_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    UNIQUE (route_id, version_no),
    FOREIGN KEY (program_id, route_id) REFERENCES routes (program_id, id),
    FOREIGN KEY (program_id, departure_base_id) REFERENCES organizations (program_id, id),
    FOREIGN KEY (program_id, return_base_id) REFERENCES organizations (program_id, id)
);

CREATE TABLE route_stops (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id       uuid         NOT NULL REFERENCES programs (id),
    route_version_id uuid         NOT NULL,
    facility_id      uuid         NOT NULL,
    sequence_no      integer      NOT NULL CHECK (sequence_no >= 1),
    instruction      varchar(500),
    UNIQUE (program_id, id),
    UNIQUE (route_version_id, sequence_no),
    FOREIGN KEY (program_id, route_version_id) REFERENCES route_versions (program_id, id),
    FOREIGN KEY (program_id, facility_id) REFERENCES facilities (program_id, id)
);

CREATE TABLE route_capture_templates (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id         uuid         NOT NULL REFERENCES programs (id),
    route_stop_id      uuid         NOT NULL,
    sequence_no        integer      NOT NULL CHECK (sequence_no >= 1),
    label              varchar(50)  NOT NULL,
    instruction        varchar(500),
    reference_media_id uuid,
    required           boolean      NOT NULL DEFAULT true,
    UNIQUE (program_id, id),
    UNIQUE (route_stop_id, sequence_no),
    FOREIGN KEY (program_id, route_stop_id) REFERENCES route_stops (program_id, id),
    FOREIGN KEY (program_id, reference_media_id) REFERENCES media (program_id, id)
);
