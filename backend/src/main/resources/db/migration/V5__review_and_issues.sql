-- DB 설계 v1.5 8절: 검토 작업·판정·문제(사건)·사진 연결, 한 판정의 여러 문제(D-25·26)
-- 우선순위 컬럼은 두지 않는다(D-10·D-13). 검토 목록은 접수순이다.

CREATE TABLE review_tasks (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id              uuid         NOT NULL REFERENCES programs (id),
    facility_id             uuid         NOT NULL,
    patrol_stop_id          uuid,
    reinspection_request_id uuid,
    type                    varchar(30)  NOT NULL CHECK (type IN ('INSPECTION', 'CAPTURE_FAILURE', 'REPAIR_VERIFICATION')),
    status                  varchar(20)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED')),
    trigger_reason          varchar(30)  NOT NULL CHECK (trigger_reason IN ('ISSUE_SUSPECTED', 'AI_NEEDS_REVIEW', 'NO_ISSUE_SAMPLE',
                                                                            'QUALITY_RETRY_EXHAUSTED', 'TECHNICAL_FAILURE', 'PRIVACY_HOLD',
                                                                            'FACILITY_MISMATCH')),
    -- 같은 원인의 재전달 중복 방지 키. 원인 종류+대상 ID(+세대). 세대 구성은 구현 선택(DB 설계 8절).
    trigger_key             varchar(200) NOT NULL,
    -- 생성 당시 관리 부서(이력용). 조회 관할은 facilities.managing_department_id 현재 값으로 판단한다.
    assigned_department_id  uuid         NOT NULL,
    claimed_by              uuid REFERENCES users (id),
    version                 bigint       NOT NULL DEFAULT 0,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    UNIQUE (program_id, trigger_key),
    FOREIGN KEY (program_id, facility_id) REFERENCES facilities (program_id, id),
    FOREIGN KEY (patrol_stop_id, facility_id) REFERENCES patrol_stops (id, facility_id),
    FOREIGN KEY (program_id, assigned_department_id) REFERENCES organizations (program_id, id),
    CHECK ((type IN ('INSPECTION', 'CAPTURE_FAILURE')) = (patrol_stop_id IS NOT NULL)),
    CHECK ((type = 'REPAIR_VERIFICATION') = (reinspection_request_id IS NOT NULL)),
    CHECK (status <> 'IN_REVIEW' OR claimed_by IS NOT NULL)
);
CREATE INDEX ix_review_tasks_list ON review_tasks (program_id, status, created_at, id);
CREATE INDEX ix_review_tasks_facility ON review_tasks (facility_id, status);

CREATE TABLE review_task_inspections (
    program_id     uuid NOT NULL REFERENCES programs (id),
    review_task_id uuid NOT NULL,
    inspection_id  uuid NOT NULL,
    PRIMARY KEY (review_task_id, inspection_id),
    FOREIGN KEY (program_id, review_task_id) REFERENCES review_tasks (program_id, id),
    FOREIGN KEY (program_id, inspection_id) REFERENCES inspections (program_id, id)
);
CREATE INDEX ix_review_task_inspections_inspection ON review_task_inspections (inspection_id);

CREATE TABLE review_decisions (
    id                           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id                   uuid         NOT NULL REFERENCES programs (id),
    review_task_id               uuid         NOT NULL,
    analysis_result_id           uuid,
    classification_correction_id uuid,
    classification_version       integer      CHECK (classification_version >= 0),
    decided_by                   uuid         NOT NULL REFERENCES users (id),
    decision                     varchar(30)  NOT NULL CHECK (decision IN ('ISSUE_CONFIRMED', 'NO_ISSUE', 'FOLLOW_UP_REQUIRED',
                                                                           'RESOLVE_CAPTURE_PROBLEM')),
    follow_up_type               varchar(20)  CHECK (follow_up_type IN ('REPHOTO', 'SITE_VISIT')),
    reason                       varchar(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    decided_at                   timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    FOREIGN KEY (program_id, review_task_id) REFERENCES review_tasks (program_id, id),
    FOREIGN KEY (program_id, analysis_result_id) REFERENCES analysis_results (program_id, id),
    FOREIGN KEY (program_id, classification_correction_id) REFERENCES classification_corrections (program_id, id),
    CHECK ((decision = 'FOLLOW_UP_REQUIRED') = (follow_up_type IS NOT NULL)),
    -- 판정 근거 3키(업무 API 9.1절): AI 근거가 없으면 모두 NULL, 정정 없는 AI 판단은 version 0, 정정 판단은 정정 ID와 1 이상
    CHECK (analysis_result_id IS NOT NULL OR (classification_correction_id IS NULL AND classification_version IS NULL)),
    CHECK (analysis_result_id IS NULL OR classification_version IS NOT NULL),
    CHECK (classification_version IS NULL OR (classification_version = 0) = (classification_correction_id IS NULL))
);
CREATE INDEX ix_review_decisions_task ON review_decisions (review_task_id, decided_at);

CREATE TABLE issues (
    id                              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id                      uuid         NOT NULL REFERENCES programs (id),
    facility_id                     uuid         NOT NULL,
    defect_code                     varchar(50)  NOT NULL,
    summary                         varchar(500) NOT NULL CHECK (length(btrim(summary)) BETWEEN 1 AND 500),
    status                          varchar(30)  NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'REPAIR_REQUESTED', 'IN_REPAIR', 'AWAITING_VERIFICATION', 'CLOSED')),
    assigned_department_id          uuid         NOT NULL,
    source_review_decision_id       uuid,
    previous_issue_id               uuid,
    current_reinspection_request_id uuid,
    opened_at                       timestamptz  NOT NULL DEFAULT now(),
    closed_at                       timestamptz,
    version                         bigint       NOT NULL DEFAULT 0,
    created_at                      timestamptz  NOT NULL DEFAULT now(),
    updated_at                      timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (program_id, id),
    FOREIGN KEY (program_id, facility_id) REFERENCES facilities (program_id, id),
    FOREIGN KEY (program_id, assigned_department_id) REFERENCES organizations (program_id, id),
    FOREIGN KEY (program_id, source_review_decision_id) REFERENCES review_decisions (program_id, id),
    FOREIGN KEY (program_id, previous_issue_id) REFERENCES issues (program_id, id),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CHECK (status <> 'AWAITING_VERIFICATION' OR current_reinspection_request_id IS NOT NULL),
    CHECK (previous_issue_id IS NULL OR previous_issue_id <> id)
);
-- 같은 시설+코드의 일률 UNIQUE는 두지 않는다. 다른 부위일 수 있어 담당자가 신규/연결을 고른다(D-25).
CREATE INDEX ix_issues_department ON issues (program_id, assigned_department_id, status, opened_at);
CREATE INDEX ix_issues_facility ON issues (facility_id, status);

CREATE TABLE issue_inspections (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id    uuid         NOT NULL REFERENCES programs (id),
    issue_id      uuid         NOT NULL,
    inspection_id uuid         NOT NULL,
    linked_by     uuid         NOT NULL REFERENCES users (id),
    linked_at     timestamptz  NOT NULL DEFAULT now(),
    unlinked_by   uuid REFERENCES users (id),
    unlinked_at   timestamptz,
    reason        varchar(500),
    FOREIGN KEY (program_id, issue_id) REFERENCES issues (program_id, id),
    FOREIGN KEY (program_id, inspection_id) REFERENCES inspections (program_id, id),
    CHECK ((unlinked_at IS NULL) = (unlinked_by IS NULL)),
    CHECK (unlinked_at IS NULL OR unlinked_at >= linked_at)
);
CREATE UNIQUE INDEX ux_issue_inspections_active ON issue_inspections (issue_id, inspection_id) WHERE unlinked_at IS NULL;
CREATE INDEX ix_issue_inspections_inspection ON issue_inspections (inspection_id);

CREATE TABLE review_decision_issues (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id         uuid         NOT NULL REFERENCES programs (id),
    review_decision_id uuid         NOT NULL,
    sequence_no        integer      NOT NULL CHECK (sequence_no >= 1),
    inspection_id      uuid         NOT NULL,
    issue_id           uuid         NOT NULL,
    mode               varchar(10)  NOT NULL CHECK (mode IN ('CREATE', 'LINK')),
    defect_code_snapshot  varchar(50)  NOT NULL,
    summary_snapshot      varchar(500) NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (review_decision_id, sequence_no),
    UNIQUE (review_decision_id, inspection_id, issue_id),
    FOREIGN KEY (program_id, review_decision_id) REFERENCES review_decisions (program_id, id),
    FOREIGN KEY (program_id, inspection_id) REFERENCES inspections (program_id, id),
    FOREIGN KEY (program_id, issue_id) REFERENCES issues (program_id, id)
);
-- 한 판정의 처분 대상 사진이 하나인지, 작업·시설과 일치하는지는 판정 트랜잭션에서 검사한다(업무 API 9.1절).
