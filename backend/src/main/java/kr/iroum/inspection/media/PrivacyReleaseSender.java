package kr.iroum.inspection.media;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import kr.iroum.inspection.analysis.client.AiClient;
import kr.iroum.inspection.analysis.client.AiClient.AiException;
import kr.iroum.inspection.common.audit.AuditLog;
import tools.jackson.databind.JsonNode;

/**
 * 개인정보 검토 결정의 AI 전송과 보류 해제 공개(업무 API 9.3절 2~4단계). privacy_reviews 행 자체를 전송 대기열(트랜잭션 outbox)로 쓴다
 * (provider_sync_status=PENDING, next_attempt_at).
 * <ol>
 * <li>전송할 행을 짧은 트랜잭션으로 점유(next_attempt_at을 호출 제한 시간 뒤로)하고, 잠금 없이 AI POST /audit/masking/{photoId}에 ok 불리언과 개인정보 없는 연결 표지(note=결정 ID, PHOTO-15)만 보낸다.</li>
 * <li>재승인(release PENDING)이 성공(hidden=false)하면 AI의 현재 사진 버전을 확인한다. 결정 때 버전보다 새 버전이면 그 사본을 받아 두고
 * 공개하지 않는다(INVALIDATED REMASKED).</li>
 * <li>반영: media → privacy_reviews 순으로 잠그고, 처리가 아직 PENDING이며 지금 사진이 결정 시점과 같은 행·버전이고 QUARANTINED일 때만 APPROVED로
 * 공개하고 COMPLETED로 기록한다. 공개는 사진 조회만 복구한다(구도 채택·판정·활동은 그대로).</li>
 * </ol>
 * 일시 장애(연결·시간 초과·5xx·429)만 설정 한도까지 자동 재시도(30초부터 2배, 상한 10분)하고 소진하면 FAILED(RETRY_EXHAUSTED).
 * 형식·권한·대상 오류(4xx)와 hidden=false가 아닌 성공 응답은 PROVIDER_REJECTED로 두고 반복하지 않는다. AI 오류 code는 last_error_code에만 남긴다.
 * 전송 순서(PHOTO-11·13): 같은 사진은 한 번에 하나, 결정 순서대로 보낸다. 점유(sending_until)는 HTTP 제한 시간 두 설정 중 큰 값의 2배이고,
 * 일시 장애로 끊긴 요청은 AI가 늦게 반영할 수 있으므로 AI 호출 제한 시간만큼 같은 사진의 다음 전송(자기 재시도 포함)을 미룬다.
 */
@Component
public class PrivacyReleaseSender {

    private static final Logger log = LoggerFactory.getLogger(PrivacyReleaseSender.class);
    /** 가림 점검 기록의 note: 개인정보 없는 연결 표지(결정 ID). AI 알림 reason으로 돌아와 우리 결정의 알림임을 확인하는 데 쓴다(PHOTO-15). */
    public static final String AUDIT_NOTE_PREFIX = "itda-privacy-review:";

    private final JdbcTemplate jdbc;
    private final AiClient ai;
    private final MediaStorage storage;
    private final PrivacyHolds holds;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double multiplier;
    private final Duration maxBackoff;
    private final Duration claim;
    private final Duration settle;

    public PrivacyReleaseSender(JdbcTemplate jdbc, AiClient ai, MediaStorage storage, PrivacyHolds holds, AuditLog audit, Clock clock,
            PlatformTransactionManager txManager, @Value("${inspection.privacy-release.max-auto-attempts:5}") int maxAttempts,
            @Value("${inspection.privacy-release.initial-backoff:30s}") Duration initialBackoff,
            @Value("${inspection.privacy-release.backoff-multiplier:2}") double multiplier,
            @Value("${inspection.privacy-release.max-backoff:10m}") Duration maxBackoff,
            @Value("${inspection.privacy-release.request-timeout:30s}") Duration requestTimeout,
            @Value("${inspection.ai.request-timeout:30s}") Duration aiRequestTimeout) {
        this.jdbc = jdbc;
        this.ai = ai;
        this.storage = storage;
        this.holds = holds;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.multiplier = multiplier;
        this.maxBackoff = maxBackoff;
        // 점유는 실제 HTTP 호출 제한(AI 클라이언트)보다 반드시 길어야 한다. 두 설정 중 큰 값의 2배.
        this.claim = claimFor(requestTimeout, aiRequestTimeout);
        // 끊긴 요청(시간 초과·연결 끊김·5xx)은 AI가 늦게 반영할 수 있어 그동안 같은 사진의 다음 전송을 막는다.
        this.settle = aiRequestTimeout;
    }

    @Scheduled(initialDelayString = "${inspection.privacy-release.interval:10s}", fixedDelayString = "${inspection.privacy-release.interval:10s}")
    public void drainQuietly() {
        try {
            drain();
        } catch (RuntimeException e) {
            log.warn("개인정보 검토 전송 실패(다음 주기 재시도): {}", e.getClass().getSimpleName());
        }
    }

    /** 지금 보낼 수 있는 행을 모두 보낸다. 처리한 수. */
    public int drain() {
        int n = 0;
        for (int i = 0; i < 50; i++) {
            Map<String, Object> row = tx.execute(s -> claimOne());
            if (row == null) {
                return n;
            }
            send(row);
            n++;
        }
        return n;
    }

    /**
     * 보낼 결정 하나를 점유한다. 같은 사진(같은 AI photo_id의 사본들)은 한 번에 하나만, 결정 순서대로 보낸다:
     * 같은 사진의 다른 결정을 보내는 중이거나(sending_until) 더 앞선 미전송 결정이 있으면 건너뛴다.
     * 잠금: 그 사진의 media 행들(ID 순) → privacy_reviews.
     */
    private Map<String, Object> claimOne() {
        Instant now = clock.instant();
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                SELECT r.id, r.media_id, m.provider_photo_id FROM privacy_reviews r JOIN media m ON m.id = r.media_id
                WHERE r.provider_sync_status = 'PENDING' AND r.next_attempt_at <= ? AND (r.sending_until IS NULL OR r.sending_until <= ?)
                ORDER BY r.created_at, r.id LIMIT 20""", Timestamp.from(now), Timestamp.from(now));
        for (Map<String, Object> c : candidates) {
            UUID mediaId = (UUID) c.get("media_id");
            String photo = (String) c.get("provider_photo_id");
            jdbc.queryForList("SELECT id FROM media WHERE id = ? OR (CAST(? AS varchar) IS NOT NULL AND provider_photo_id = ?) ORDER BY id FOR UPDATE",
                    mediaId, photo, photo);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT r.id, r.media_id, r.ok, r.release_status, r.decided_provider_revision, m.provider_photo_id, r.created_at
                    FROM privacy_reviews r JOIN media m ON m.id = r.media_id
                    WHERE r.id = ? AND r.provider_sync_status = 'PENDING' AND r.next_attempt_at <= ?
                      AND (r.sending_until IS NULL OR r.sending_until <= ?) FOR UPDATE OF r""",
                    c.get("id"), Timestamp.from(now), Timestamp.from(now));
            if (rows.isEmpty()) {
                continue;
            }
            Boolean blocked = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM privacy_reviews x JOIN media mx ON mx.id = x.media_id
                        WHERE x.id <> ? AND (x.media_id = ? OR (CAST(? AS varchar) IS NOT NULL AND mx.provider_photo_id = ?))
                          AND ((x.sending_until IS NOT NULL AND x.sending_until > ?)
                            OR (x.provider_sync_status = 'PENDING' AND (x.created_at, x.id) < (?, ?))))""",
                    Boolean.class, c.get("id"), mediaId, photo, photo, Timestamp.from(now), rows.get(0).get("created_at"), c.get("id"));
            if (Boolean.TRUE.equals(blocked)) {
                continue;
            }
            jdbc.update("UPDATE privacy_reviews SET next_attempt_at = ?, sending_until = ? WHERE id = ?", Timestamp.from(now.plus(claim)),
                    Timestamp.from(now.plus(claim)), c.get("id"));
            return rows.get(0);
        }
        return null;
    }

    private enum Outcome {
        SENT, TRANSIENT, REJECTED
    }

    private void send(Map<String, Object> row) {
        UUID reviewId = (UUID) row.get("id");
        String photoId = (String) row.get("provider_photo_id");
        boolean ok = Boolean.TRUE.equals(row.get("ok"));
        boolean release = "PENDING".equals(row.get("release_status"));
        Outcome outcome;
        String code = null;
        Integer aiRevision = null;
        String newKey = null;
        AiClient.MaskedImage newer = null;
        if (photoId == null) {
            outcome = Outcome.REJECTED;
            code = "NO_PROVIDER_PHOTO_ID";
        } else {
            try {
                JsonNode resp = ai.auditMasking(photoId, ok, AUDIT_NOTE_PREFIX + reviewId);
                if (ok && !(resp.has("hidden") && resp.get("hidden").isBoolean() && !resp.get("hidden").asBoolean())) {
                    outcome = Outcome.REJECTED;
                    code = "HIDDEN_NOT_RELEASED";
                } else {
                    outcome = Outcome.SENT;
                    if (release) {
                        // 해제 뒤 AI의 현재 사진 버전 확인(재가림 반영 여부). 새 버전이면 사본을 받아 둔다.
                        AiClient.MaskedImage img = ai.maskedImage(photoId);
                        aiRevision = img.revision();
                        Integer decided = (Integer) row.get("decided_provider_revision");
                        if (decided != null && img.revision() > decided) {
                            UUID program = jdbc.queryForObject("SELECT program_id FROM media WHERE id = ?", UUID.class, row.get("media_id"));
                            newKey = program + "/remask/" + photoId + "/r" + img.revision() + "-" + UUID.randomUUID()
                                    + ("image/png".equals(img.contentType()) ? ".png" : ".jpg");
                            storage.put(MediaStorage.BUCKET, newKey, img.bytes());
                            newer = img;
                        }
                    }
                }
            } catch (AiException e) {
                outcome = e.transientFailure() ? Outcome.TRANSIENT : Outcome.REJECTED;
                code = e.code() != null ? e.code() : "HTTP_" + e.status();
            } catch (java.io.UncheckedIOException e) {
                outcome = Outcome.TRANSIENT;
                code = "STORAGE_FAILED";
            }
        }
        Outcome o = outcome;
        String c = code;
        Integer rev = aiRevision;
        String key = newKey;
        AiClient.MaskedImage img = newer;
        try {
            Boolean kept = tx.execute(s -> apply(reviewId, (UUID) row.get("media_id"), o, c, rev, key, img));
            if (key != null && !Boolean.TRUE.equals(kept)) {
                storage.deleteQuietly(MediaStorage.BUCKET, key);
            }
        } catch (RuntimeException e) {
            if (key != null) {
                storage.deleteQuietly(MediaStorage.BUCKET, key);
            }
            throw e;
        }
    }

    /** 새 사본을 실제로 썼으면 true. */
    private boolean apply(UUID reviewId, UUID mediaId, Outcome outcome, String code, Integer aiRevision, String newKey,
            AiClient.MaskedImage newer) {
        boolean kept = false;
        if (newer != null) {
            // 재가림 반영(순회 → … → media → privacy_reviews 순서). 진행 중 재승인은 REMASKED로 무효가 된다.
            kept = holds.remask(mediaId, newKey, newer.bytes(), sha256(newer.bytes()), newer.contentType(), newer.revision());
        } else {
            jdbc.queryForList("SELECT id FROM media WHERE id = ? FOR UPDATE", mediaId);
        }
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM privacy_reviews WHERE id = ? FOR UPDATE", reviewId);
        Instant now0 = clock.instant();
        jdbc.update("UPDATE privacy_reviews SET sending_until = ? WHERE id = ?",
                outcome == Outcome.TRANSIENT ? Timestamp.from(now0.plus(settle)) : null, reviewId);
        Instant now = clock.instant();
        int attempts = ((Number) r.get("attempt_count")).intValue() + 1;
        int cycle = ((Number) r.get("cycle_attempt_count")).intValue() + 1;
        String release = (String) r.get("release_status");
        UUID program = (UUID) r.get("program_id");
        switch (outcome) {
            case SENT -> {
                jdbc.update("""
                        UPDATE privacy_reviews SET provider_sync_status = 'SENT', attempt_count = ?, cycle_attempt_count = ?, next_attempt_at = NULL,
                            last_error_code = NULL, version = version + 1 WHERE id = ?""", attempts, cycle, reviewId);
                if ("PENDING".equals(release)) {
                    Map<String, Object> m = jdbc.queryForMap("""
                            SELECT privacy_status, provider_image_revision, superseded_by_media_id FROM media WHERE id = ?""", mediaId);
                    boolean same = m.get("superseded_by_media_id") == null && "QUARANTINED".equals(m.get("privacy_status"))
                            && Objects.equals(m.get("provider_image_revision"), r.get("decided_provider_revision"))
                            && (aiRevision == null || Objects.equals(aiRevision, r.get("decided_provider_revision")));
                    if (same) {
                        jdbc.update("UPDATE media SET privacy_status = 'APPROVED', version = version + 1, updated_at = ? WHERE id = ?",
                                Timestamp.from(now), mediaId);
                        jdbc.update("UPDATE privacy_reviews SET release_status = 'COMPLETED', completed_at = ? WHERE id = ?", Timestamp.from(now),
                                reviewId);
                        audit.record(program, null, "PRIVACY_RELEASED", "MEDIA", mediaId, null, Map.of("privacyStatus", "QUARANTINED"),
                                Map.of("privacyStatus", "APPROVED", "releaseId", reviewId));
                    } else {
                        jdbc.update("""
                                UPDATE privacy_reviews SET release_status = 'INVALIDATED', invalid_reason = 'REMASKED' WHERE id = ?
                                    AND release_status = 'PENDING'""", reviewId);
                    }
                }
            }
            case TRANSIENT -> {
                boolean exhausted = cycle >= maxAttempts;
                if (exhausted) {
                    jdbc.update("""
                            UPDATE privacy_reviews SET provider_sync_status = 'FAILED', attempt_count = ?, cycle_attempt_count = ?, next_attempt_at = NULL,
                                last_error_code = ?, release_status = CASE WHEN release_status = 'PENDING' THEN 'FAILED' ELSE release_status END,
                                failure_reason = CASE WHEN release_status = 'PENDING' THEN 'RETRY_EXHAUSTED' ELSE failure_reason END,
                                version = version + 1 WHERE id = ?""", attempts, cycle, code, reviewId);
                } else {
                    jdbc.update("""
                            UPDATE privacy_reviews SET attempt_count = ?, cycle_attempt_count = ?, next_attempt_at = ?, last_error_code = ?,
                                version = version + 1 WHERE id = ?""", attempts, cycle,
                            Timestamp.from(now.plus(backoff(cycle).compareTo(settle) >= 0 ? backoff(cycle) : settle)), code, reviewId);
                }
            }
            case REJECTED -> jdbc.update("""
                    UPDATE privacy_reviews SET provider_sync_status = 'FAILED', attempt_count = ?, cycle_attempt_count = ?, next_attempt_at = NULL,
                        last_error_code = ?, release_status = CASE WHEN release_status = 'PENDING' THEN 'FAILED' ELSE release_status END,
                        failure_reason = CASE WHEN release_status = 'PENDING' THEN 'PROVIDER_REJECTED' ELSE failure_reason END,
                        version = version + 1 WHERE id = ?""", attempts, cycle, code, reviewId);
        }
        if (outcome != Outcome.SENT) {
            log.warn("개인정보 검토 AI 전송 {}: code={}", outcome, code);
        }
        return kept;
    }

    static Duration claimFor(Duration releaseTimeout, Duration aiTimeout) {
        return (releaseTimeout.compareTo(aiTimeout) >= 0 ? releaseTimeout : aiTimeout).multipliedBy(2);
    }

    /** cycle번째 실패 뒤 다음 시도까지: 초기 간격 × 배수^(cycle-1), 상한. */
    Duration backoff(int cycle) {
        double seconds = initialBackoff.toSeconds() * Math.pow(multiplier, Math.max(0, cycle - 1));
        return Duration.ofSeconds((long) Math.min(seconds, maxBackoff.toSeconds()));
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
