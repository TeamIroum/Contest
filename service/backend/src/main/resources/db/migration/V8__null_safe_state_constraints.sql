-- 검토 지적 보완(2026-10-12): CHECK는 결과가 NULL이면 통과하므로 `col = '값'`·`col IN (...)` 조건이 NULL 값을 막지 못했다.
-- 이미 적용된 V3·V6을 고치지 않고(Flyway 체크섬 유지) 같은 의미를 NULL에 안전한 제약으로 추가한다. 기존 제약은 그대로 둔다.

-- 1) 종료 상태는 복귀 구분이 담당자 확인으로 확정돼야 한다(DB 설계 6절: 배정 전원 담당자 확인, 확정 구분 기준).
--    COMPLETED = 확정 NORMAL, ABORTED = 확정 ABORT + 중도 종료 사유. 확정자·확정 시각은 기존 쌍 제약으로 함께 강제된다.
ALTER TABLE patrols
    ADD CONSTRAINT patrols_completed_requires_confirmed_normal CHECK (
        activity_status <> 'COMPLETED'
        OR (return_reason IS NOT DISTINCT FROM 'NORMAL' AND return_reason_source IS NOT DISTINCT FROM 'CONFIRMED')),
    ADD CONSTRAINT patrols_aborted_requires_confirmed_abort CHECK (
        activity_status <> 'ABORTED'
        OR (return_reason IS NOT DISTINCT FROM 'ABORT' AND return_reason_source IS NOT DISTINCT FROM 'CONFIRMED'
            AND abort_reason IS NOT NULL));

-- 2) 장소 건너뛰기는 사유가 필수다(업무 API 6절 skip-events reason). 값 목록은 기존 제약이 검사한다.
ALTER TABLE patrol_events
    ADD CONSTRAINT patrol_events_skip_requires_reason CHECK (type <> 'STOP_SKIPPED' OR reason_code IS NOT NULL);

-- 3) 재점검 배정의 구도는 그 배정의 순회 소속이어야 한다.
--    구도 → 순회 장소 → 순회를 복합 FK로 잇기 위해 순회 장소 ID를 복제한다.
ALTER TABLE capture_slots ADD CONSTRAINT capture_slots_id_patrol_stop_id_key UNIQUE (id, patrol_stop_id);

ALTER TABLE reinspection_assignments ADD COLUMN patrol_stop_id uuid;
UPDATE reinspection_assignments a SET patrol_stop_id = s.patrol_stop_id FROM capture_slots s WHERE s.id = a.capture_slot_id;
ALTER TABLE reinspection_assignments
    ALTER COLUMN patrol_stop_id SET NOT NULL,
    ADD CONSTRAINT reinspection_assignments_slot_stop_fkey
        FOREIGN KEY (capture_slot_id, patrol_stop_id) REFERENCES capture_slots (id, patrol_stop_id),
    ADD CONSTRAINT reinspection_assignments_stop_patrol_fkey
        FOREIGN KEY (patrol_stop_id, patrol_id) REFERENCES patrol_stops (id, patrol_id);
