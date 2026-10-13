-- 06단계 VERIFY-03: 수리 확인 준비 때 검토한 증거 사본(media ID·사진 버전)을 고정한다(업무 API 10절 "준비 후 증거가 바뀌면 새 준비").
-- 판정은 사진 시도의 현재 사본이 이 사본과 같을 때만 허용하고, 이력은 준비 당시 사본을 보여 준다.
-- 이 마이그레이션 전에 만든 증거 행은 값이 없어(NULL) 그 준비로 판정할 수 없다(VERIFY-02의 prepared_request_version과 같은 처리).
ALTER TABLE verification_evidence
    ADD COLUMN media_id uuid,
    ADD COLUMN media_version integer CHECK (media_version >= 1),
    ADD CONSTRAINT verification_evidence_media_fkey FOREIGN KEY (program_id, media_id) REFERENCES media (program_id, id),
    ADD CONSTRAINT verification_evidence_media_pair CHECK ((media_id IS NULL) = (media_version IS NULL));
