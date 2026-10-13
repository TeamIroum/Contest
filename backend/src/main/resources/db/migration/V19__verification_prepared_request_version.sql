-- 06단계 VERIFY-02: 수리 확인 준비가 만들어질 때의 재점검 요청 version(준비로 올린 뒤 값)을 남긴다.
-- 판정은 이 값이 요청의 현재 version과 같은 준비(= 그 뒤 새 준비·배정 변경·추가 확인이 없는 준비)만 허용한다.
-- 이 마이그레이션 전에 만든 미판정 준비는 값이 없어 판정에 쓸 수 없고 새 준비가 필요하다.
ALTER TABLE verifications ADD COLUMN prepared_request_version bigint CHECK (prepared_request_version >= 0);
