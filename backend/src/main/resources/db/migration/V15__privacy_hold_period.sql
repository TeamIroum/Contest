-- 04단계 PHOTO-05(2026-10-13): 개인정보 보류 기간의 시작 시각(업무 API 9.3절 "privacyRelease는 현재 보류 기간의 가장 최근 재승인 결정").
-- 보류(AI image_hidden·담당자 부족 판정)마다 갱신한다. 이 시각보다 앞선 재승인 처리는 현재 보류 기간의 결정이 아니다.
-- 재가림으로 만든 새 사본은 이전 사본의 값을 이어받는다.
ALTER TABLE media ADD COLUMN privacy_held_at timestamptz;

-- AI 이벤트 사진 ID로 현재 사본을 찾는다(대체된 사본 제외).
CREATE INDEX ix_media_provider_current ON media (provider_photo_id) WHERE provider_photo_id IS NOT NULL AND superseded_by_media_id IS NULL;
