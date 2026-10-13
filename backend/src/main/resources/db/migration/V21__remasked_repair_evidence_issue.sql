-- 06단계 MEDIA-UP-02: V18 이후 재가림으로 만든 보수 증거 사본은 issue_id를 이어받지 않았다. 이전 사본 계보에서 문제 연결을 채운다.
-- 보수 이력(repair_event_media)이 대체된 사본을 가리키면 그 계보의 현재 사본으로 옮긴다(재가림 시 다른 포인터와 같은 처리).
WITH RECURSIVE chain(root_id, id, issue_id) AS (
    SELECT m.id, m.id, m.issue_id FROM media m WHERE m.purpose = 'REPAIR_EVIDENCE' AND m.issue_id IS NOT NULL
    UNION ALL
    SELECT c.root_id, n.id, c.issue_id FROM media n JOIN chain c ON n.source_media_id = c.id
)
UPDATE media m SET issue_id = c.issue_id FROM chain c WHERE m.id = c.id AND m.issue_id IS NULL AND m.purpose = 'REPAIR_EVIDENCE';

WITH RECURSIVE forward(start_id, id, next_id) AS (
    SELECT rem.media_id, m.id, m.superseded_by_media_id FROM repair_event_media rem JOIN media m ON m.id = rem.media_id
    WHERE m.superseded_by_media_id IS NOT NULL
    UNION ALL
    SELECT f.start_id, m.id, m.superseded_by_media_id FROM media m JOIN forward f ON m.id = f.next_id
)
UPDATE repair_event_media rem SET media_id = f.id FROM forward f WHERE rem.media_id = f.start_id AND f.next_id IS NULL;
