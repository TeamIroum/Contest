-- 04단계 PHOTO-11(2026-10-13, 리뷰 재현 보완): 개인정보 검토 결정의 AI 전송을 사진(AI photo_id)별로 한 번에 하나, 결정 순서대로 보낸다.
-- sending_until: 이 결정을 AI에 보내는 중이라는 표시(점유 시각 + 호출 제한 시간×2). 그동안 같은 사진의 다른 결정은 보내지 않는다.
ALTER TABLE privacy_reviews ADD COLUMN sending_until timestamptz;
CREATE INDEX ix_privacy_reviews_sending ON privacy_reviews (media_id) WHERE sending_until IS NOT NULL;
