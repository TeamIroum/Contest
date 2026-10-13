-- DB 설계 v1.5 9절: 보수·재점검·수리 확인. 화면과 API는 후속 범위(D-09)이며 테이블만 먼저 만든다.

CREATE TABLE repair_events (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id    uuid         NOT NULL REFERENCES programs (id),
    issue_id      uuid         NOT NULL,
    type          varchar(20)  NOT NULL CHECK (type IN ('REQUESTED', 'STARTED', 'COMPLETED', 'RE_REQUESTED')),
    actor_user_id uuid         NOT NULL REFERENCES users (id),
    department_id uuid,
    description   varchar(1000),
    occurred_at   timestamptz  NOT NULL,
    recorded_at   timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    UNIQUE (id, issue_id),
    FOREIGN KEY (program_id, issue_id) REFERENCES issues (program_id, id),
    FOREIGN KEY (program_id, department_id) REFERENCES organizations (program_id, id)
);

CREATE TABLE repair_event_media (
    program_id      uuid NOT NULL REFERENCES programs (id),
    repair_event_id uuid NOT NULL,
    media_id        uuid NOT NULL,
    PRIMARY KEY (repair_event_id, media_id),
    FOREIGN KEY (program_id, repair_event_id) REFERENCES repair_events (program_id, id),
    FOREIGN KEY (program_id, media_id) REFERENCES media (program_id, id)
);

CREATE TABLE reinspection_requests (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id         uuid        NOT NULL REFERENCES programs (id),
    facility_id        uuid        NOT NULL,
    issue_id           uuid,
    repair_event_id    uuid UNIQUE,
    review_decision_id uuid UNIQUE,
    request_type       varchar(20) NOT NULL CHECK (request_type IN ('REPHOTO', 'SITE_VISIT')),
    status             varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    requested_by       uuid        NOT NULL REFERENCES users (id),
    due_at             timestamptz,
    version            bigint      NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    UNIQUE (program_id, id, issue_id),
    FOREIGN KEY (program_id, facility_id) REFERENCES facilities (program_id, id),
    FOREIGN KEY (program_id, issue_id) REFERENCES issues (program_id, id),
    FOREIGN KEY (repair_event_id, issue_id) REFERENCES repair_events (id, issue_id),
    FOREIGN KEY (program_id, review_decision_id) REFERENCES review_decisions (program_id, id),
    -- 발생 원인은 보수 완료 또는 추가 확인 판정 중 정확히 하나
    CHECK (num_nonnulls(repair_event_id, review_decision_id) = 1),
    CHECK (repair_event_id IS NULL OR issue_id IS NOT NULL)
);
CREATE INDEX ix_reinspection_requests_status ON reinspection_requests (program_id, status, due_at);

CREATE TABLE reinspection_assignments (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id      uuid        NOT NULL REFERENCES programs (id),
    request_id      uuid        NOT NULL,
    capture_slot_id uuid        NOT NULL,
    patrol_id       uuid        NOT NULL,
    assigned_by     uuid        NOT NULL REFERENCES users (id),
    assigned_at     timestamptz NOT NULL DEFAULT now(),
    cancelled_at    timestamptz,
    completed_at    timestamptz,
    FOREIGN KEY (program_id, request_id) REFERENCES reinspection_requests (program_id, id),
    FOREIGN KEY (program_id, capture_slot_id) REFERENCES capture_slots (program_id, id),
    FOREIGN KEY (program_id, patrol_id) REFERENCES patrols (program_id, id),
    CHECK (cancelled_at IS NULL OR completed_at IS NULL),
    -- 한 요청의 활성 구도는 모두 한 순회 안에 있어야 한다(DB 설계 9절)
    EXCLUDE USING gist (request_id WITH =, patrol_id WITH <>) WHERE (cancelled_at IS NULL AND completed_at IS NULL)
);
CREATE UNIQUE INDEX ux_reinspection_assignments_active ON reinspection_assignments (request_id, capture_slot_id)
    WHERE cancelled_at IS NULL AND completed_at IS NULL;

CREATE TABLE verifications (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id          uuid         NOT NULL REFERENCES programs (id),
    request_id          uuid         NOT NULL,
    analysis_result_id  uuid,
    verification_method varchar(20)  NOT NULL CHECK (verification_method IN ('PHOTO', 'SITE_VISIT')),
    decided_by          uuid REFERENCES users (id),
    decision            varchar(20)  CHECK (decision IN ('CLOSE', 'REPAIR_AGAIN', 'FOLLOW_UP_REQUIRED')),
    follow_up_type      varchar(20)  CHECK (follow_up_type IN ('REPHOTO', 'SITE_VISIT')),
    reason              varchar(500),
    verified_at         timestamptz,
    version             bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    FOREIGN KEY (program_id, request_id) REFERENCES reinspection_requests (program_id, id),
    FOREIGN KEY (program_id, analysis_result_id) REFERENCES analysis_results (program_id, id),
    -- 준비 상태는 판단 3값 모두 NULL, 확정 상태는 모두 값이 있다
    CHECK ((decided_by IS NULL) = (decision IS NULL) AND (decision IS NULL) = (verified_at IS NULL)),
    CHECK (decision IS NULL OR (decision = 'FOLLOW_UP_REQUIRED') = (follow_up_type IS NOT NULL)),
    CHECK (decision IS NOT NULL OR follow_up_type IS NULL)
);

CREATE TABLE verification_evidence (
    program_id      uuid        NOT NULL REFERENCES programs (id),
    verification_id uuid        NOT NULL,
    inspection_id   uuid        NOT NULL,
    role            varchar(10) NOT NULL CHECK (role IN ('BEFORE', 'AFTER')),
    PRIMARY KEY (verification_id, inspection_id, role),
    FOREIGN KEY (program_id, verification_id) REFERENCES verifications (program_id, id),
    FOREIGN KEY (program_id, inspection_id) REFERENCES inspections (program_id, id)
);

CREATE TABLE verification_notes (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id      uuid          NOT NULL REFERENCES programs (id),
    verification_id uuid          NOT NULL,
    author_user_id  uuid          NOT NULL REFERENCES users (id),
    note            varchar(2000) NOT NULL CHECK (length(btrim(note)) >= 1),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    FOREIGN KEY (program_id, verification_id) REFERENCES verifications (program_id, id)
);

-- 순환 참조 FK. 부모 INSERT → 자식 INSERT → 포인터 UPDATE 순서로 쓴다(DB 설계 11.1절).
ALTER TABLE issues
    ADD CONSTRAINT fk_issues_current_request
        FOREIGN KEY (program_id, current_reinspection_request_id, id) REFERENCES reinspection_requests (program_id, id, issue_id);
ALTER TABLE review_tasks
    ADD CONSTRAINT fk_review_tasks_request FOREIGN KEY (program_id, reinspection_request_id) REFERENCES reinspection_requests (program_id, id);
ALTER TABLE analysis_jobs
    ADD CONSTRAINT fk_analysis_jobs_verification FOREIGN KEY (program_id, verification_id) REFERENCES verifications (program_id, id);
