-- DB 설계 v1.5 4절: 사업·조직·계정·역할·참여자·평소 조
-- 업무 테이블은 program_id와 UNIQUE(program_id, id)를 두고 사업 간 연결을 복합 FK로 막는다. users는 사업 공통 계정이다.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE programs (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(100) NOT NULL,
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    timezone    varchar(40)  NOT NULL DEFAULT 'Asia/Seoul',
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE organizations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id  uuid         NOT NULL REFERENCES programs (id),
    parent_id   uuid,
    type        varchar(20)  NOT NULL CHECK (type IN ('AGENCY', 'DEPARTMENT', 'MEETING_POINT')),
    name        varchar(100) NOT NULL,
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    CHECK (parent_id IS NULL OR parent_id <> id),
    FOREIGN KEY (program_id, parent_id) REFERENCES organizations (program_id, id)
);
-- 조직 순환은 서버에서 검사한다(DB 설계 4절).

CREATE TABLE users (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name                     varchar(50)  NOT NULL,
    phone_ciphertext         bytea,
    phone_lookup_hmac        bytea UNIQUE,
    login_id                 varchar(50) UNIQUE,
    password_hash            varchar(200),
    status                   varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    auth_version             bigint       NOT NULL DEFAULT 0 CHECK (auth_version >= 0),
    password_change_required boolean      NOT NULL DEFAULT false,
    password_changed_at      timestamptz,
    password_failed_count    integer      NOT NULL DEFAULT 0 CHECK (password_failed_count >= 0),
    locked_until             timestamptz,
    version                  bigint       NOT NULL DEFAULT 0,
    created_at               timestamptz  NOT NULL DEFAULT now(),
    updated_at               timestamptz  NOT NULL DEFAULT now(),
    -- 전화 로그인은 암호문과 검색용 HMAC을 함께, 관리자 로그인은 ID와 비밀번호 해시를 함께 둔다.
    CHECK ((phone_ciphertext IS NULL) = (phone_lookup_hmac IS NULL)),
    CHECK ((login_id IS NULL) = (password_hash IS NULL))
);

CREATE TABLE user_role_scopes (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id          uuid        NOT NULL REFERENCES programs (id),
    user_id             uuid        NOT NULL REFERENCES users (id),
    organization_id     uuid        NOT NULL,
    role                varchar(30) NOT NULL CHECK (role IN ('SENIOR', 'ACTIVITY_MANAGER', 'FACILITY_MANAGER', 'PROGRAM_MANAGER', 'SYSTEM_ADMIN')),
    include_descendants boolean     NOT NULL DEFAULT false,
    granted_at          timestamptz NOT NULL DEFAULT now(),
    revoked_at          timestamptz,
    FOREIGN KEY (program_id, organization_id) REFERENCES organizations (program_id, id),
    CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);
CREATE UNIQUE INDEX ux_user_role_scopes_active ON user_role_scopes (user_id, organization_id, role) WHERE revoked_at IS NULL;
CREATE INDEX ix_user_role_scopes_user ON user_role_scopes (user_id);

-- D-23 비밀번호 변경 제한 세션. 비밀값은 해시만 저장한다.
CREATE TABLE password_change_sessions (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES users (id),
    purpose      varchar(30) NOT NULL DEFAULT 'PASSWORD_CHANGE' CHECK (purpose = 'PASSWORD_CHANGE'),
    secret_hash  bytea       NOT NULL UNIQUE,
    auth_version bigint      NOT NULL,
    issued_at    timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz,
    CHECK (expires_at > issued_at)
);
CREATE INDEX ix_password_change_sessions_user ON password_change_sessions (user_id, expires_at);

CREATE TABLE participants (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id              uuid        NOT NULL REFERENCES programs (id),
    user_id                 uuid        NOT NULL REFERENCES users (id),
    organization_id         uuid        NOT NULL,
    status                  varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ENDED')),
    available_schedule_json jsonb,
    version                 bigint      NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    UNIQUE (program_id, user_id),
    FOREIGN KEY (program_id, organization_id) REFERENCES organizations (program_id, id)
);

CREATE TABLE teams (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id      uuid         NOT NULL REFERENCES programs (id),
    organization_id uuid         NOT NULL,
    name            varchar(50)  NOT NULL,
    status          varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    FOREIGN KEY (program_id, organization_id) REFERENCES organizations (program_id, id)
);

CREATE TABLE team_memberships (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id     uuid     NOT NULL REFERENCES programs (id),
    team_id        uuid     NOT NULL,
    participant_id uuid     NOT NULL,
    seat_no        smallint NOT NULL CHECK (seat_no IN (1, 2, 3)),
    valid_from     date     NOT NULL,
    valid_until    date,
    created_at     timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (program_id, team_id) REFERENCES teams (program_id, id),
    FOREIGN KEY (program_id, participant_id) REFERENCES participants (program_id, id),
    CHECK (valid_until IS NULL OR valid_until > valid_from),
    -- 유효 기간이 겹치는 같은 조의 자리·참여자 중복 금지(DB 설계 4절)
    EXCLUDE USING gist (team_id WITH =, seat_no WITH =, daterange(valid_from, valid_until) WITH &&),
    EXCLUDE USING gist (team_id WITH =, participant_id WITH =, daterange(valid_from, valid_until) WITH &&)
);
