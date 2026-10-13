-- 06단계 MEDIA-UP-01: 보수 증거 사진(REPAIR_EVIDENCE)이 어느 문제를 위해 올라왔는지 남긴다(업무 API 10절 POST /admin/media의 issueId).
-- 보수 완료의 증거 연결(repair_event_media)은 같은 문제의 사진만 허용하고, 사진 조회 권한은 그 문제 시설의 현재 관할로 판단한다.
ALTER TABLE media
    ADD COLUMN issue_id uuid,
    ADD CONSTRAINT media_issue_fkey FOREIGN KEY (program_id, issue_id) REFERENCES issues (program_id, id),
    ADD CONSTRAINT media_issue_only_repair_evidence CHECK (issue_id IS NULL OR purpose = 'REPAIR_EVIDENCE');
CREATE INDEX ix_media_issue ON media (issue_id) WHERE issue_id IS NOT NULL;
