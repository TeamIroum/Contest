-- DB 설계 v1.5 10절: 멱등 요청, 감사, outbox, 알림, 보고 작업, AI 이벤트 수신

CREATE TABLE idempotency_requests (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              uuid         NOT NULL REFERENCES users (id),
    -- HTTP 메서드 + 경로 템플릿(예: "POST /admin/review-tasks/{id}/decisions")
    operation            varchar(200) NOT NULL,
    idempotency_key      uuid         NOT NULL,
    -- 경로 ID·본문·파일 해시를 포함한 요청 해시
    request_hash         bytea        NOT NULL,
    state                varchar(20)  NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    response_code        smallint     CHECK (response_code BETWEEN 100 AND 599),
    resource_id          uuid,
    -- 당시 결과 스냅샷. 임시 비밀번호 등 비밀 값은 저장하지 않는다.
    result_snapshot_json jsonb,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    expires_at           timestamptz  NOT NULL,
    UNIQUE (user_id, operation, idempotency_key),
    CHECK ((state = 'COMPLETED') = (response_code IS NOT NULL)),
    CHECK (expires_at > created_at)
);
CREATE INDEX ix_idempotency_requests_expiry ON idempotency_requests (expires_at);

CREATE TABLE audit_events (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id    uuid REFERENCES programs (id),
    actor_user_id uuid REFERENCES users (id),
    action        varchar(100) NOT NULL,
    resource_type varchar(50)  NOT NULL,
    resource_id   uuid         NOT NULL,
    reason        varchar(500),
    -- 토큰·전화번호 평문·사진 URL은 넣지 않는다.
    before_json   jsonb,
    after_json    jsonb,
    request_id    varchar(64),
    created_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_events_resource ON audit_events (program_id, resource_type, resource_id, created_at);

CREATE TABLE outbox_events (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type   varchar(100) NOT NULL,
    aggregate_id uuid         NOT NULL,
    payload_json jsonb        NOT NULL,
    status       varchar(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RETRY', 'DONE', 'FAILED')),
    next_run_at  timestamptz  NOT NULL DEFAULT now(),
    attempts     integer      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_outbox_events_runnable ON outbox_events (next_run_at) WHERE status IN ('PENDING', 'RETRY');

CREATE TABLE notifications (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid         NOT NULL REFERENCES users (id),
    type          varchar(50)  NOT NULL,
    resource_type varchar(50)  NOT NULL,
    resource_id   uuid         NOT NULL,
    message       varchar(500) NOT NULL,
    read_at       timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notifications (user_id, read_at, created_at);

CREATE TABLE report_jobs (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id       uuid        NOT NULL REFERENCES programs (id),
    organization_id  uuid        NOT NULL,
    week_start       date        NOT NULL,
    status           varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    requested_by     uuid        NOT NULL REFERENCES users (id),
    metrics_json     jsonb,
    narrative        text,
    narrative_source varchar(20) CHECK (narrative_source IN ('llm', 'template')),
    generated_at     timestamptz,
    source_cutoff_at timestamptz,
    revision_no      integer     NOT NULL DEFAULT 1 CHECK (revision_no >= 1),
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, week_start, revision_no),
    FOREIGN KEY (program_id, organization_id) REFERENCES organizations (program_id, id)
    -- 주 시작 요일은 업무 API 11절에서 아직 제안 단계라 제약으로 고정하지 않는다.
);

CREATE TABLE ai_event_cursors (
    provider         varchar(50) NOT NULL,
    generation       varchar(50) NOT NULL,
    last_applied_seq bigint      NOT NULL DEFAULT 0 CHECK (last_applied_seq >= 0),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, generation)
);

CREATE TABLE ai_event_inbox (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider       varchar(50) NOT NULL,
    generation     varchar(50) NOT NULL,
    seq            bigint      NOT NULL CHECK (seq >= 1),
    photo_id       varchar(80),
    type           varchar(30) NOT NULL CHECK (type IN ('image_remasked', 'image_hidden', 'image_unhidden', 'result_updated')),
    image_revision integer     CHECK (image_revision >= 1),
    payload_json   jsonb       NOT NULL,
    status         varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RETRY', 'APPLIED', 'FAILED')),
    attempts       integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_run_at    timestamptz NOT NULL DEFAULT now(),
    received_at    timestamptz NOT NULL DEFAULT now(),
    applied_at     timestamptz,
    UNIQUE (provider, generation, seq),
    CHECK ((status = 'APPLIED') = (applied_at IS NOT NULL))
);
CREATE INDEX ix_ai_event_inbox_pending ON ai_event_inbox (provider, generation, seq) WHERE status <> 'APPLIED';
