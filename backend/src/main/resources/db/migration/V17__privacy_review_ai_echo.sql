-- 04단계 PHOTO-14(2026-10-13, 실제 AI 연결 검증에서 재현): AI는 우리 가림 점검 기록(POST /audit/masking)마다 image_hidden·image_unhidden 알림을
-- 남긴다. 우리 부족 판정(ok:false)이 만든 보류 알림(메아리)은 한 번만 확인 처리하고 업무 보류를 다시 적용하지 않는다.
-- ai_echo_consumed_at: 이 결정의 보류 알림을 이벤트 동기화가 메아리로 처리한 시각.
ALTER TABLE privacy_reviews ADD COLUMN ai_echo_consumed_at timestamptz;
