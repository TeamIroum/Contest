package kr.iroum.inspection.analysis.event;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import kr.iroum.inspection.analysis.AnalysisSnapshots;
import kr.iroum.inspection.analysis.AnalyzeOutcome;
import kr.iroum.inspection.analysis.client.AiClient;
import kr.iroum.inspection.analysis.client.AiClient.AiException;
import kr.iroum.inspection.media.MediaStorage;
import kr.iroum.inspection.media.PrivacyHolds;
import kr.iroum.inspection.media.PrivacyReleaseSender;
import kr.iroum.inspection.review.ReviewTaskOutbox;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI GET /events 동기화(업무 API 15.3절, DB 설계 10·12절, 04단계 공백 G4).
 * <ol>
 * <li>받은 이벤트를 ai_event_inbox에 넣는다(UNIQUE(provider, generation, seq)로 중복 방지).</li>
 * <li>커서(ai_event_cursors.last_applied_seq)보다 큰 seq를 순서대로 하나씩 처리한다. 업무 변경·inbox 반영·커서 전진을 한 트랜잭션으로
 * 확정하고, 실패하면 inbox만 RETRY로 남기고 커서를 넘기지 않는다(다음 주기에 같은 이벤트부터). AI의 last_seq는 완료 커서로 쓰지 않는다.</li>
 * <li>AI 사진·결과 조회는 트랜잭션 밖에서 먼저 하고, 반영할 때 다시 확인한다.</li>
 * </ol>
 * 이벤트별 처리: image_remasked → 새 검사본·새 media 행(15.3절), image_hidden → 즉시 보류(PRIVACY_HOLD; reason이 우리 부족 판정 요청을 가리키는 메아리만 제외,
 * PHOTO-14·15), image_unhidden → 공개하지 않음
 * (9.3절 재승인 순서만 공개 근거), result_updated → GET /results 스냅샷 추가만(사람 판정·구도·완료 활동 유지).
 * 우리 사진이 아닌 photo_id·이미 대체된 사본의 이벤트는 반영할 것 없이 커서만 넘긴다.
 * 세대 초기화(AI 이벤트 로그 재시작)는 감지 방법이 미정(G4)이라, AI last_seq가 우리 커서보다 작으면 처리를 멈추고 경보 로그만 남긴다.
 */
@Component
public class AiEventSync {

    public static final String PROVIDER = "ai-v2";
    private static final Logger log = LoggerFactory.getLogger(AiEventSync.class);
    private static final Set<String> TYPES = Set.of("image_remasked", "image_hidden", "image_unhidden", "result_updated");

    private final JdbcTemplate jdbc;
    private final AiClient ai;
    private final MediaStorage storage;
    private final PrivacyHolds holds;
    private final AnalysisSnapshots snapshots;
    private final ReviewTaskOutbox reviews;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String generation;

    public AiEventSync(JdbcTemplate jdbc, AiClient ai, MediaStorage storage, PrivacyHolds holds, AnalysisSnapshots snapshots,
            ReviewTaskOutbox reviews, JsonMapper json, Clock clock, PlatformTransactionManager txManager,
            @Value("${inspection.ai.event-generation:g1}") String generation) {
        this.jdbc = jdbc;
        this.ai = ai;
        this.storage = storage;
        this.holds = holds;
        this.snapshots = snapshots;
        this.reviews = reviews;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.generation = generation;
    }

    public record SyncReport(int received, int applied, boolean blocked) {
    }

    @Scheduled(initialDelayString = "${inspection.ai.events-interval:15s}", fixedDelayString = "${inspection.ai.events-interval:15s}")
    public void scheduled() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.warn("AI 이벤트 동기화 실패(다음 주기 재시도): {}", e.getClass().getSimpleName());
        }
    }

    public SyncReport sync() {
        jdbc.update("INSERT INTO ai_event_cursors (provider, generation) VALUES (?, ?) ON CONFLICT DO NOTHING", PROVIDER, generation);
        long cursor = cursor();
        int received = 0;
        try {
            JsonNode page;
            long after = Math.max(cursor, maxInboxSeq());
            do {
                page = ai.events(after, 100);
                long last = page.path("last_seq").asLong(0);
                if (last < cursor) {
                    log.error("AI 이벤트 순번이 커서보다 작음(세대 초기화 의심, G4): last_seq={} cursor={} generation={}", last, cursor, generation);
                    return new SyncReport(0, 0, true);
                }
                for (JsonNode e : page.path("events")) {
                    received += store(e);
                    after = Math.max(after, e.path("seq").asLong());
                }
            } while (page.path("events").size() >= 100);
        } catch (AiException e) {
            log.warn("AI 이벤트 조회 실패: HTTP {} code={}", e.status(), e.code());
        }
        int applied = 0;
        while (true) {
            // 커서 다음의 가장 앞 미처리 이벤트만 본다. 그 이벤트가 재시도 대기 중이면 뒤 이벤트를 건너뛰어 처리하지 않고 멈춘다
            // (커서가 앞 이벤트를 지나치면 그 이벤트는 다시 처리되지 않는다).
            Optional<Map<String, Object>> next = jdbc.queryForList("""
                    SELECT id, seq, type, photo_id, image_revision, next_run_at, payload_json->>'reason' AS reason FROM ai_event_inbox
                    WHERE provider = ? AND generation = ? AND status <> 'APPLIED' AND seq > ? ORDER BY seq LIMIT 1""",
                    PROVIDER, generation, cursor()).stream().findFirst();
            if (next.isEmpty()) {
                return new SyncReport(received, applied, false);
            }
            if (((Timestamp) next.get().get("next_run_at")).toInstant().isAfter(clock.instant())) {
                return new SyncReport(received, applied, true);
            }
            if (!process(next.get())) {
                return new SyncReport(received, applied, true);
            }
            applied++;
        }
    }

    private int store(JsonNode e) {
        String type = e.path("type").asString("");
        long seq = e.path("seq").asLong(0);
        if (!TYPES.contains(type) || seq < 1) {
            log.warn("알 수 없는 AI 이벤트는 건너뜀: type={} seq={}", type, seq);
            return 0;
        }
        JsonNode rev = e.get("image_revision");
        return jdbc.update("""
                INSERT INTO ai_event_inbox (provider, generation, seq, photo_id, type, image_revision, payload_json, next_run_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) ON CONFLICT (provider, generation, seq) DO NOTHING""",
                PROVIDER, generation, seq, e.path("photo_id").asString(null), type, rev != null && rev.isIntegralNumber() ? rev.asInt() : null,
                json.writeValueAsString(e), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
    }

    /** 이벤트 하나 반영. 성공(또는 반영할 것 없음)이면 true, 다시 시도해야 하면 false. */
    private boolean process(Map<String, Object> event) {
        UUID inboxId = (UUID) event.get("id");
        long seq = ((Number) event.get("seq")).longValue();
        String type = (String) event.get("type");
        String photoId = (String) event.get("photo_id");
        Integer revision = (Integer) event.get("image_revision");
        String reason = (String) event.get("reason");
        // 1) 트랜잭션 밖 준비: 새 검사본·새 결과 조회
        String storedKey = null;
        Fetched fetched = Fetched.NONE;
        try {
            UUID media = currentMedia(photoId);
            if ("image_remasked".equals(type) && media != null && revision != null && revisionOf(media) < revision) {
                AiClient.MaskedImage img = null;
                try {
                    img = ai.maskedImage(photoId);
                } catch (AiException e) {
                    // 410(AI 보류 중)은 해제 뒤 버전으로 따라잡는다(PHOTO-10). 그 밖의 대상 오류는 새 사본을 받을 수 없으므로
                    // 누락이 있던 지금 사본을 공개로 두지 않고 보류 쪽으로 반영한다(PHOTO-18).
                    if (retryable(e) || e.status() == 410) {
                        throw e;
                    }
                    log.warn("재가림 사본 조회 대상 오류(HTTP {}): 지금 사본을 보류로 돌림 seq={}", e.status(), seq);
                }
                if (img != null) {
                    storedKey = programOf(media) + "/remask/" + photoId + "/r" + img.revision() + "-" + UUID.randomUUID()
                            + ("image/png".equals(img.contentType()) ? ".png" : ".jpg");
                    storage.put(MediaStorage.BUCKET, storedKey, img.bytes());
                    fetched = new Fetched(img, null, null);
                } else {
                    fetched = new Fetched(null, null, true);
                }
            } else if ("result_updated".equals(type) && photoId != null && jobOf(photoId) != null) {
                fetched = new Fetched(null, ai.result(photoId).orElse(null), null);
            } else if ("image_hidden".equals(type) && media != null && ownHoldDecision(photoId, reason) != null
                    && "APPROVED".equals(statusOf(media))) {
                // 우리 과거 부족 판정의 알림인데 업무 사본은 이미 공개 상태: 지난 알림인지, 그 요청이 늦게 실행돼 AI를 지금 보류시켰는지 AI 현재 상태로 가른다.
                // 같은 사진의 결정이 보내지는 중이거나 보낼 차례(미전송)면 그 결과가 정해질 때까지 이 알림을 미룬다(고정 대기 시간을 가정하지 않음).
                if (unsettledDecision(photoId)) {
                    retryLater(inboxId, "DECISION_UNSETTLED");
                    return false;
                }
                // 200으로 공개를 확인한 경우만 지난 알림이다. 일시 장애·인증(401)·권한(403)은 확인하지 못한 것이므로 미루고(커서 유지),
                // 410은 보류, 그 밖의 대상 오류(404 등)도 공개를 확인하지 못했으므로 보류 쪽으로 반영한다(PHOTO-18).
                try {
                    ai.maskedImage(photoId);
                    fetched = new Fetched(null, null, false);
                } catch (AiException e) {
                    if (retryable(e)) {
                        throw e;
                    }
                    if (e.status() != 410) {
                        log.warn("AI 현재 상태 확인 대상 오류(HTTP {} code={}): 공개를 확인하지 못해 보류 쪽으로 반영 seq={}", e.status(), e.code(), seq);
                    }
                    fetched = new Fetched(null, null, true);
                }
            }
        } catch (AiException e) {
            if (retryable(e)) {
                if (!e.transientFailure()) {
                    log.error("AI 인증·권한 거절(HTTP {} code={}): API 키·설정 확인 필요. 이벤트를 처리하지 않고 동기화를 멈춤 seq={}", e.status(), e.code(), seq);
                }
                retryLater(inboxId, "AI " + e.status());
                return false;
            }
            // 410 보류·404 없음: 이 이벤트로 바꿀 것이 없다(보류는 image_hidden 이벤트가 반영).
            log.info("AI 이벤트 대상 조회 거절, 반영 없이 넘김: seq={} type={} HTTP {}", seq, type, e.status());
        } catch (RuntimeException e) {
            retryLater(inboxId, e.getClass().getSimpleName());
            return false;
        }
        // 2) 업무 변경·inbox·커서를 한 트랜잭션으로
        String key = storedKey;
        Fetched f = fetched;
        try {
            Boolean kept = tx.execute(s -> apply(inboxId, seq, type, photoId, reason, key, f));
            if (key != null && !Boolean.TRUE.equals(kept)) {
                storage.deleteQuietly(MediaStorage.BUCKET, key);
            }
            reviews.drain();
            return true;
        } catch (RuntimeException e) {
            if (key != null) {
                storage.deleteQuietly(MediaStorage.BUCKET, key);
            }
            log.warn("AI 이벤트 반영 실패(커서 유지): seq={} type={} {}", seq, type, e.getClass().getSimpleName());
            retryLater(inboxId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** aiHidden: 우리 과거 부족 판정의 알림을 처리하기 전 확인한 AI 현재 보류 여부(확인하지 않았으면 null). */
    private record Fetched(AiClient.MaskedImage image, JsonNode result, Boolean aiHidden) {
        static final Fetched NONE = new Fetched(null, null, null);
    }

    /** 새 사본을 실제로 썼으면 true. */
    private boolean apply(UUID inboxId, long seq, String type, String photoId, String reason, String storedKey, Fetched f) {
        Long cursor = jdbc.queryForObject("SELECT last_applied_seq FROM ai_event_cursors WHERE provider = ? AND generation = ? FOR UPDATE",
                Long.class, PROVIDER, generation);
        if (cursor != null && seq <= cursor) {
            return false;
        }
        boolean kept = false;
        UUID media = currentMedia(photoId);
        switch (type) {
            case "image_remasked" -> {
                if (media != null && f.image() != null) {
                    kept = holds.remask(media, storedKey, f.image().bytes(), sha256(f.image().bytes()), f.image().contentType(),
                            f.image().revision());
                } else if (media != null && Boolean.TRUE.equals(f.aiHidden())) {
                    holds.lockTargets(media);
                    if ("APPROVED".equals(statusOf(media))) {
                        holds.hold(media);
                    }
                }
            }
            case "image_hidden" -> {
                if (media != null) {
                    applyHoldEvent(media, photoId, reason, f, seq);
                }
            }
            case "image_unhidden" -> log.info("AI 보류 해제 알림: 업무 공개는 재승인 순서로만 한다(9.3절). seq={}", seq);
            case "result_updated" -> {
                UUID job = jobOf(photoId);
                if (job != null && f.result() != null) {
                    UUID program = jdbc.queryForObject("SELECT program_id FROM analysis_jobs WHERE id = ?", UUID.class, job);
                    snapshots.append(program, job, f.result(), media, clock.instant());
                    if (!AnalyzeOutcome.retryPending(f.result())) {
                        jdbc.update("""
                                UPDATE analysis_jobs SET status = 'SUCCEEDED', next_run_at = NULL, finished_at = ?, updated_at = ?
                                WHERE id = ? AND retry_owner = 'AI' AND status = 'PROCESSING'""",
                                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), job);
                    }
                }
            }
            default -> {
            }
        }
        Instant now = clock.instant();
        jdbc.update("UPDATE ai_event_inbox SET status = 'APPLIED', applied_at = ?, attempts = attempts + 1 WHERE id = ?", Timestamp.from(now),
                inboxId);
        jdbc.update("UPDATE ai_event_cursors SET last_applied_seq = ?, updated_at = ? WHERE provider = ? AND generation = ?", seq,
                Timestamp.from(now), PROVIDER, generation);
        return kept;
    }

    /**
     * image_hidden 반영(D-28, PHOTO-14~16). AI는 보류 상태가 바뀔 때만 알림을 남기고 reason에 요청 note(우리 결정 ID 표지)를 담는다.
     * <ol>
     * <li>우리 결정의 알림이 아니면(다른 점검 경로·AI 내부) 새 보류: 즉시 보류하고 진행 중 재승인을 무효로 한다.</li>
     * <li>우리 부족 판정의 알림이고 업무 사본도 보류 중이면 상태가 일치한다. 다시 보류하지 않아 그 뒤의 재승인을 무효로 만들지 않는다.</li>
     * <li>우리 부족 판정의 알림인데 업무 사본은 공개 상태이면, 같은 사진의 결정이 보내지는 중·보낼 차례면 미루고(커서 유지), 아니면 AI 현재 상태로 가른다. 지금도 AI가 보류(410)면 그 과거 요청이 늦게 실행돼 AI를 다시 보류한
     * 것이다(예: 시간 초과로 끊긴 ok:false가 최신 ok:true 공개 뒤 실행). 업무 사본도 보류하고 검토로 넘겨 담당자가 다시 결정한다(공개 결정을 자동으로
     * 다시 보내지 않음). AI가 보류가 아니면 공개 전에 생긴 지난 알림이다.</li>
     * </ol>
     * 판단이 끝난 우리 결정에는 ai_echo_consumed_at을 남긴다.
     */
    private void applyHoldEvent(UUID media, String photoId, String reason, Fetched f, long seq) {
        UUID own = ownHoldDecision(photoId, reason);
        if (own == null) {
            holds.hold(media);
            return;
        }
        String status = statusOf(media);
        if ("APPROVED".equals(status)) {
            if (f.aiHidden() == null) {
                // 준비 단계 뒤 상태가 바뀌었다(공개됨). AI 현재 상태를 다시 확인하도록 이 알림을 다시 시도한다.
                throw new IllegalStateException("AI 현재 상태 확인 필요");
            }
            if (f.aiHidden()) {
                // AI 상태 확인 뒤 반영 전에 같은 사진의 새 결정이 생겼거나 보내지는 중이면 판단을 다시 한다(보류 잠금 아래 확인).
                holds.lockTargets(media);
                if (unsettledDecision(photoId) || !"APPROVED".equals(statusOf(media))) {
                    throw new IllegalStateException("같은 사진의 결정이 정해지는 중");
                }
                log.warn("우리 과거 부족 판정이 공개 뒤 AI에서 늦게 실행돼 다시 보류됨: 업무 사본 보류·검토 인계 review={} seq={}", own, seq);
                holds.hold(media);
            } else {
                log.info("공개 전에 생긴 우리 부족 판정의 지난 알림: 반영 생략 review={} seq={}", own, seq);
            }
        } else {
            log.info("우리 부족 판정의 알림(업무 사본도 보류 중): 다시 보류하지 않음 review={} seq={}", own, seq);
        }
        jdbc.update("UPDATE privacy_reviews SET ai_echo_consumed_at = COALESCE(ai_echo_consumed_at, ?) WHERE id = ?",
                Timestamp.from(clock.instant()), own);
    }

    /** reason이 이 사진의 우리 부족 판정(ok:false) 결정 ID 표지면 그 ID, 아니면 null. */
    private UUID ownHoldDecision(String photoId, String reason) {
        if (photoId == null || reason == null || !reason.startsWith(PrivacyReleaseSender.AUDIT_NOTE_PREFIX)) {
            return null;
        }
        UUID reviewId;
        try {
            reviewId = UUID.fromString(reason.substring(PrivacyReleaseSender.AUDIT_NOTE_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
        Boolean ours = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM privacy_reviews r JOIN media m ON m.id = r.media_id
                    WHERE r.id = ? AND m.provider_photo_id = ? AND NOT r.ok)""", Boolean.class, reviewId, photoId);
        return Boolean.TRUE.equals(ours) ? reviewId : null;
    }

    private String statusOf(UUID media) {
        return jdbc.queryForObject("SELECT privacy_status FROM media WHERE id = ?", String.class, media);
    }

    /** 같은 사진(같은 AI photo_id)의 개인정보 결정이 AI에 보내지는 중(점유)이거나 아직 보내지 않은(PENDING) 것이 있는지. */
    private boolean unsettledDecision(String photoId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM privacy_reviews r JOIN media m ON m.id = r.media_id
                    WHERE m.provider_photo_id = ? AND (r.provider_sync_status = 'PENDING'
                        OR (r.sending_until IS NOT NULL AND r.sending_until > ?)))""",
                Boolean.class, photoId, Timestamp.from(clock.instant())));
    }

    /**
     * 이벤트를 처리하지 않고 다시 시도할 AI 오류. 일시 장애(연결·시간 초과·5xx·429)와 인증(401)·권한(403) 거절이다. 인증·권한 거절은 사진과 무관한
     * 설정 오류라 대상 상태를 알려 주지 않는다. 고쳐질 때까지 커서를 넘기지 않는다(경보 로그).
     */
    private static boolean retryable(AiException e) {
        return e.transientFailure() || e.status() == 401 || e.status() == 403;
    }

    private void retryLater(UUID inboxId, String reason) {
        jdbc.update("""
                UPDATE ai_event_inbox SET status = 'RETRY', attempts = attempts + 1,
                    next_run_at = CAST(? AS timestamptz) + make_interval(secs => LEAST(600, 15 * power(2, LEAST(attempts, 6))))
                WHERE id = ? AND status <> 'APPLIED'""",
                Timestamp.from(clock.instant()), inboxId);
        log.warn("AI 이벤트 재시도 예약: {}", reason);
    }

    private long cursor() {
        Long c = jdbc.queryForObject("SELECT last_applied_seq FROM ai_event_cursors WHERE provider = ? AND generation = ?", Long.class, PROVIDER,
                generation);
        return c == null ? 0 : c;
    }

    private long maxInboxSeq() {
        Long m = jdbc.queryForObject("SELECT max(seq) FROM ai_event_inbox WHERE provider = ? AND generation = ?", Long.class, PROVIDER,
                generation);
        return m == null ? 0 : m;
    }

    private UUID currentMedia(String photoId) {
        if (photoId == null) {
            return null;
        }
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM media WHERE provider_photo_id = ? AND superseded_by_media_id IS NULL AND deleted_at IS NULL
                ORDER BY created_at DESC LIMIT 1""", UUID.class, photoId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private UUID jobOf(String photoId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM analysis_jobs WHERE provider_photo_id = ?", UUID.class, photoId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private int revisionOf(UUID media) {
        Integer r = jdbc.queryForObject("SELECT provider_image_revision FROM media WHERE id = ?", Integer.class, media);
        return r == null ? 0 : r;
    }

    private UUID programOf(UUID media) {
        return jdbc.queryForObject("SELECT program_id FROM media WHERE id = ?", UUID.class, media);
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
