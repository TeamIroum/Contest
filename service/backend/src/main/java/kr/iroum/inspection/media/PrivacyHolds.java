package kr.iroum.inspection.media;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import kr.iroum.inspection.inspection.StopProgress;
import kr.iroum.inspection.review.ReviewTaskOutbox;

/**
 * 저장된 사진의 개인정보 보류·재가림(업무 API 9·9.3·15.3절, DB 설계 7·12절). AI 이벤트와 담당자 부족 판정이 같은 규칙을 쓴다.
 * 호출자 트랜잭션 안에서 쓰며, 잠금 순서는 대상 순회를 먼저 조회한 뒤 순회(6) → 장소(9) → 구도(10) → 사진 시도(11) → media(13) → privacy_reviews(14),
 * 같은 단계는 ID 오름차순이다. media를 먼저 잠근 채 순회를 잠그지 않는다.
 * <ul>
 * <li>보류: media를 QUARANTINED로 바꿔 조회·판독을 즉시 막고, 그 사진을 승인 사본으로 쓰던 시도의 승인 포인터와 구도 채택을 풀어 검토로 넘긴다
 * (PRIVACY_HOLD). 진행 중 재승인은 INVALIDATED(NEW_PRIVACY_HOLD). 활동 상태·사람 판정은 바꾸지 않는다.</li>
 * <li>재가림: 새 검사본을 새 media 행으로 저장하고 이전 행은 대체됨으로 표시해 조회하지 않는다. 승인·참고·보수 증거 포인터를 새 행으로 옮기고
 * (보수 증거의 문제 연결 issue_id도 이어받음)
 * 진행 중 재승인은 INVALIDATED(REMASKED). 개인정보 상태와 보류 기간은 이어받는다(보류 중 재가림은 보류 유지).</li>
 * </ul>
 */
@Component
public class PrivacyHolds {

    private final JdbcTemplate jdbc;
    private final StopProgress stops;
    private final ReviewTaskOutbox reviews;
    private final Clock clock;

    public PrivacyHolds(JdbcTemplate jdbc, StopProgress stops, ReviewTaskOutbox reviews, Clock clock) {
        this.jdbc = jdbc;
        this.stops = stops;
        this.reviews = reviews;
        this.clock = clock;
    }

    /** 보류를 적용하고 승인 포인터가 풀린 사진 시도 ID를 돌려준다. */
    public List<UUID> hold(UUID mediaId) {
        Targets t = lockTargets(mediaId);
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE media SET privacy_status = 'QUARANTINED', privacy_held_at = ?, version = version + 1, updated_at = ? WHERE id = ?""",
                Timestamp.from(now), Timestamp.from(now), mediaId);
        List<UUID> affected = new ArrayList<>();
        for (Map<String, Object> i : jdbc.queryForList("""
                SELECT i.id, i.program_id, i.capture_slot_id, s.patrol_stop_id, s.accepted_inspection_id = i.id AS accepted
                FROM inspections i JOIN capture_slots s ON s.id = i.capture_slot_id WHERE i.approved_media_id = ? ORDER BY i.id""", mediaId)) {
            UUID inspection = (UUID) i.get("id");
            jdbc.update("""
                    UPDATE inspections SET approved_media_id = NULL,
                        capture_status = CASE WHEN capture_status = 'ACCEPTED' THEN 'REVIEW_REQUESTED' ELSE capture_status END, updated_at = ?
                    WHERE id = ?""", Timestamp.from(now), inspection);
            if (Boolean.TRUE.equals(i.get("accepted"))) {
                jdbc.update("""
                        UPDATE capture_slots SET accepted_inspection_id = NULL, resolution = 'REVIEW_REQUESTED', version = version + 1, updated_at = ?
                        WHERE id = ?""", Timestamp.from(now), i.get("capture_slot_id"));
                stops.settle((UUID) i.get("patrol_stop_id"), true);
            }
            reviews.enqueue((UUID) i.get("program_id"), inspection, (UUID) i.get("patrol_stop_id"), (UUID) i.get("capture_slot_id"),
                    "PRIVACY_HOLD");
            affected.add(inspection);
        }
        invalidateReleases(mediaId, "NEW_PRIVACY_HOLD");
        bump(t.patrols());
        return affected;
    }

    /**
     * 재가림 반영. 지금 행의 AI 사진 버전이 revision보다 낮을 때만 새 행을 만든다(같거나 높으면 false, 호출자가 저장 사본을 지운다).
     */
    public boolean remask(UUID mediaId, String objectKey, byte[] bytes, byte[] sha256, String contentType, int revision) {
        Targets t = lockTargets(mediaId);
        Map<String, Object> old = jdbc.queryForMap("""
                SELECT program_id, uploaded_by, purpose, privacy_status, provider_photo_id, provider_image_revision, privacy_held_at,
                    superseded_by_media_id, issue_id FROM media WHERE id = ?""", mediaId);
        Integer current = (Integer) old.get("provider_image_revision");
        if (old.get("superseded_by_media_id") != null || (current != null && current >= revision)) {
            return false;
        }
        Instant now = clock.instant();
        UUID fresh = jdbc.queryForObject("""
                INSERT INTO media (program_id, uploaded_by, bucket, object_key, purpose, privacy_status, source_media_id, content_type, size_bytes,
                    sha256, provider_photo_id, provider_image_revision, privacy_held_at, issue_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
                old.get("program_id"), old.get("uploaded_by"), MediaStorage.BUCKET, objectKey, old.get("purpose"), old.get("privacy_status"),
                mediaId, contentType, (long) bytes.length, sha256, old.get("provider_photo_id"), revision, old.get("privacy_held_at"),
                old.get("issue_id"), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("UPDATE media SET superseded_by_media_id = ?, version = version + 1, updated_at = ? WHERE id = ?", fresh,
                Timestamp.from(now), mediaId);
        jdbc.update("UPDATE inspections SET approved_media_id = ?, updated_at = ? WHERE approved_media_id = ?", fresh, Timestamp.from(now),
                mediaId);
        jdbc.update("UPDATE capture_slots SET reference_media_id = ?, version = version + 1, updated_at = ? WHERE reference_media_id = ?", fresh,
                Timestamp.from(now), mediaId);
        // 보수 증거(MEDIA-UP-02): 새 사본도 같은 문제에 연결되고(issue_id 이어받음), 보수 이력의 증거 포인터도 새 사본으로 옮긴다.
        // 보수 완료가 같은 사진을 먼저 연결했으면 media 잠금(13)이 그 커밋을 기다리므로 여기서 함께 옮겨진다.
        jdbc.update("UPDATE repair_event_media SET media_id = ? WHERE media_id = ?", fresh, mediaId);
        invalidateReleases(mediaId, "REMASKED");
        bump(t.patrols());
        return true;
    }

    /** 진행 중(PENDING·FAILED) 재승인을 무효로 하고 아직 보내지 않은 해제 전송을 멈춘다. */
    public void invalidateReleases(UUID mediaId, String reason) {
        jdbc.queryForList("SELECT id FROM privacy_reviews WHERE media_id = ? ORDER BY id FOR UPDATE", mediaId);
        jdbc.update("""
                UPDATE privacy_reviews SET release_status = 'INVALIDATED', invalid_reason = ?, failure_reason = NULL, next_attempt_at = NULL,
                    provider_sync_status = CASE WHEN provider_sync_status = 'PENDING' THEN 'FAILED' ELSE provider_sync_status END,
                    last_error_code = CASE WHEN provider_sync_status = 'PENDING' THEN 'INVALIDATED' ELSE last_error_code END,
                    version = version + 1
                WHERE media_id = ? AND release_status IN ('PENDING', 'FAILED')""", reason, mediaId);
    }

    public record Targets(TreeSet<UUID> patrols) {
    }

    /** 대상 순회·장소·구도·시도를 먼저 조회한 뒤 잠금 순서대로 잠그고 media까지 잠근다(다시 불러도 된다). */
    public Targets lockTargets(UUID mediaId) {
        // 조상 사본까지 따라 점검 시도를 찾는다(보류 중 만든 재가림 사본은 승인 포인터가 없다, MediaLineage).
        String linked = """
                SELECT i.id FROM inspections i WHERE i.media_id IN (SELECT id FROM lineage) OR i.approved_media_id IN (SELECT id FROM lineage)""";
        TreeSet<UUID> patrols = new TreeSet<>(jdbc.queryForList(MediaLineage.CTE + """
                SELECT ps.patrol_id FROM inspections i JOIN capture_slots s ON s.id = i.capture_slot_id JOIN patrol_stops ps ON ps.id = s.patrol_stop_id
                WHERE i.id IN (""" + linked + """
                ) UNION SELECT ps.patrol_id FROM capture_slots s JOIN patrol_stops ps ON ps.id = s.patrol_stop_id
                WHERE s.reference_media_id IN (SELECT id FROM lineage)""", UUID.class, mediaId));
        for (UUID patrol : patrols) {
            jdbc.queryForList("SELECT id FROM patrols WHERE id = ? FOR UPDATE", patrol);
        }
        String slots = """
                SELECT s.id FROM capture_slots s WHERE s.reference_media_id IN (SELECT id FROM lineage)
                    OR s.id IN (SELECT capture_slot_id FROM inspections WHERE id IN (""" + linked + "))";
        jdbc.queryForList(MediaLineage.CTE + "SELECT ps.id FROM patrol_stops ps JOIN capture_slots s ON s.patrol_stop_id = ps.id WHERE s.id IN ("
                + slots + ") ORDER BY ps.id FOR UPDATE OF ps", mediaId);
        jdbc.queryForList(MediaLineage.CTE + slots + " ORDER BY s.id FOR UPDATE", mediaId);
        jdbc.queryForList(MediaLineage.CTE + linked + " ORDER BY i.id FOR UPDATE", mediaId);
        jdbc.queryForList("SELECT id FROM media WHERE id = ? FOR UPDATE", mediaId);
        return new Targets(patrols);
    }

    private void bump(TreeSet<UUID> patrols) {
        for (UUID patrol : patrols) {
            jdbc.update("UPDATE patrols SET revision = revision + 1, updated_at = now() WHERE id = ?", patrol);
        }
    }
}
