package kr.iroum.inspection.media;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse.FieldError;
import kr.iroum.inspection.common.idempotency.IdempotencyService;

/**
 * 개인정보 검토 결정과 보류 해제(재승인) 재요청(업무 API 9·9.3절, D-28). FACILITY_MANAGER만, 사진이 연결된 시설의 현재 관리 부서 기준.
 * <ul>
 * <li>부족 판정(ok=false): 언제나 가능. 즉시 보류({@link PrivacyHolds#hold})하고 AI 가림 점검 기록 {"ok": false}를 비동기로 보낸다.
 * 차단은 전송 결과를 기다리지 않는다. 진행 중 재승인은 INVALIDATED(NEW_PRIVACY_HOLD).</li>
 * <li>재승인(ok=true, 보류 중 사진): release PENDING으로 결정만 커밋한다. 공개는 {@link PrivacyReleaseSender}가 AI 해제 성공과 버전 검증 뒤에만 한다.
 * 최신 처리가 PENDING·FAILED이면 409 PRIVACY_RELEASE_CONFLICT. 보류 없는 사진의 ok=true는 release 없이 기록과 AI 점검 기록만 남긴다.</li>
 * <li>재요청: 현재 보류 기간의 최신 처리가 그 releaseId이고 FAILED일 때만 새 자동 재시도 주기를 시작한다.</li>
 * </ul>
 * 잠금: 부족 판정은 보류 순서(순회 → 장소 → 구도 → 시도 → media → privacy_reviews), 재승인·재요청은 media → privacy_reviews.
 * version은 사진 기록 version(media.version)이며 다르면 409 VERSION_CONFLICT. 대체된(재가림 전) 사본에 대한 결정도 VERSION_CONFLICT다.
 */
@Service
public class PrivacyReviewService {

    public record PrivacyRelease(UUID releaseId, String status, Instant decidedAt, int decidedMediaVersion, int attempts, Instant nextRetryAt,
            Instant completedAt, String failureReason, String invalidReason, boolean retryable) {
    }

    public record PrivacyResult(UUID mediaId, String privacyStatus, long version, String providerSyncStatus, List<UUID> affectedInspectionIds,
            Instant reviewedAt, PrivacyRelease release) {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final PrivacyHolds holds;
    private final IdempotencyService idempotency;
    private final AuditLog audit;
    private final PrivacyReleaseSender sender;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PrivacyReviewService(JdbcTemplate jdbc, AccessPolicy access, PrivacyHolds holds, IdempotencyService idempotency, AuditLog audit,
            PrivacyReleaseSender sender, Clock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.access = access;
        this.holds = holds;
        this.idempotency = idempotency;
        this.audit = audit;
        this.sender = sender;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public PrivacyResult review(AuthenticatedUser user, UUID mediaId, Boolean ok, String reason, Long version, UUID key) {
        List<FieldError> errors = new ArrayList<>();
        if (ok == null) {
            errors.add(new FieldError("ok", "REQUIRED", "가림 판정이 필요합니다."));
        }
        if (reason == null || reason.isBlank()) {
            errors.add(new FieldError("reason", "REQUIRED", "사유가 필요합니다."));
        } else if (reason.length() > 500) {
            errors.add(new FieldError("reason", "TOO_LONG", "500자 이하여야 합니다."));
        }
        if (version == null || version < 0) {
            errors.add(new FieldError("version", version == null ? "REQUIRED" : "OUT_OF_RANGE", "사진 기록 version이 필요합니다."));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, errors);
        }
        UUID program = requireJurisdiction(user, mediaId);
        Map<String, Object> identity = Map.of("mediaId", mediaId, "ok", ok, "reason", reason, "version", version);
        PrivacyResult result = tx.execute(s -> idempotency.execute(user.userId(), "POST /admin/media/{mediaId}/privacy-reviews", key, identity,
                PrivacyResult.class, 200, () -> decide(user, program, mediaId, ok, reason.strip(), version)).body());
        sender.drainQuietly();
        return result;
    }

    public PrivacyResult retryRelease(AuthenticatedUser user, UUID mediaId, UUID releaseId, Long version, UUID key) {
        List<FieldError> errors = new ArrayList<>();
        if (releaseId == null) {
            errors.add(new FieldError("releaseId", "REQUIRED", "재승인 처리 ID가 필요합니다."));
        }
        if (version == null || version < 0) {
            errors.add(new FieldError("version", version == null ? "REQUIRED" : "OUT_OF_RANGE", "사진 기록 version이 필요합니다."));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, errors);
        }
        UUID program = requireJurisdiction(user, mediaId);
        Map<String, Object> identity = Map.of("mediaId", mediaId, "releaseId", releaseId, "version", version);
        PrivacyResult result = tx.execute(s -> idempotency.execute(user.userId(), "POST /admin/media/{mediaId}/privacy-release-retries", key,
                identity, PrivacyResult.class, 202, () -> restart(user, program, mediaId, releaseId, version)).body());
        sender.drainQuietly();
        return result;
    }

    /**
     * 변경 트랜잭션 첫 잠금: 사진에 연결된 시설 행(전역 잠금 1단계, ID순, 공유)을 잠근 뒤 현재 관할을 다시 확인한다. 잠금 대기 중 관할이
     * 이관됐으면 이전 부서 담당자는 404이고 아무것도 남기지 않는다(REVIEW-05).
     */
    private void lockJurisdiction(AuthenticatedUser user, UUID mediaId) {
        for (UUID facility : new java.util.TreeSet<>(linkedFacilities(mediaId))) {
            jdbc.queryForList("SELECT id FROM facilities WHERE id = ? FOR SHARE", facility);
        }
        requireJurisdiction(user, mediaId);
    }

    /** 사진(조상 사본 포함, {@link MediaLineage})이 연결된 시설(점검 사진의 순회 장소, 참고 사진을 쓰는 구도의 장소)의 현재 관리 부서 담당자만. 역할 없음 403, 범위 밖·없음 404. */
    private UUID requireJurisdiction(AuthenticatedUser user, UUID mediaId) {
        access.requireAnyRole(user, AccessPolicy.FACILITY_MANAGER);
        for (UUID facility : linkedFacilities(mediaId)) {
            try {
                return access.requireFacility(user, facility);
            } catch (ApiException e) {
                // 다른 연결 시설로 확인한다.
            }
        }
        throw new ApiException(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다.");
    }

    private List<UUID> linkedFacilities(UUID mediaId) {
        return jdbc.queryForList(MediaLineage.CTE + """
                SELECT ps.facility_id FROM inspections i JOIN capture_slots s ON s.id = i.capture_slot_id JOIN patrol_stops ps ON ps.id = s.patrol_stop_id
                WHERE i.media_id IN (SELECT id FROM lineage) OR i.approved_media_id IN (SELECT id FROM lineage)
                UNION SELECT ps.facility_id FROM capture_slots s JOIN patrol_stops ps ON ps.id = s.patrol_stop_id
                WHERE s.reference_media_id IN (SELECT id FROM lineage)
                UNION SELECT i.facility_id FROM media m JOIN issues i ON i.id = m.issue_id WHERE m.id IN (SELECT id FROM lineage)""", UUID.class,
                mediaId);
    }

    private PrivacyResult decide(AuthenticatedUser user, UUID program, UUID mediaId, boolean ok, String reason, long version) {
        lockJurisdiction(user, mediaId);
        if (ok) {
            jdbc.queryForList("SELECT id FROM media WHERE id = ? FOR UPDATE", mediaId);
        } else {
            holds.lockTargets(mediaId);
        }
        Map<String, Object> media = requireVersion(mediaId, version);
        Instant now = clock.instant();
        List<UUID> affected = affected(mediaId);
        String status = (String) media.get("privacy_status");
        String releaseStatus = null;
        if (!ok) {
            holds.hold(mediaId);
        } else if (!"APPROVED".equals(status)) {
            Optional<Map<String, Object>> latest = latestRelease(mediaId);
            if (latest.isPresent() && List.of("PENDING", "FAILED").contains(latest.get().get("release_status"))) {
                throw releaseConflict(latest.get());
            }
            releaseStatus = "PENDING";
        }
        Integer revision = (Integer) media.get("provider_image_revision");
        UUID reviewId = jdbc.queryForObject("""
                INSERT INTO privacy_reviews (program_id, media_id, decided_by, ok, reason, media_record_version, decided_media_version,
                    decided_provider_revision, provider_sync_status, release_status, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?) RETURNING id""", UUID.class,
                program, mediaId, user.userId(), ok, reason, version, revision == null ? 1 : revision, revision, releaseStatus,
                Timestamp.from(now), Timestamp.from(now));
        // 같은 사진(같은 AI photo_id의 모든 사본)의 아직 보내지 않은 이전 결정은 보내지 않는다. 최신 결정만 AI 상태를 정한다.
        jdbc.update("""
                UPDATE privacy_reviews SET provider_sync_status = 'FAILED', last_error_code = 'SUPERSEDED', next_attempt_at = NULL, version = version + 1
                WHERE id <> ? AND provider_sync_status = 'PENDING' AND media_id IN (
                    SELECT o.id FROM media o JOIN media c ON c.id = ? WHERE o.id = c.id OR (c.provider_photo_id IS NOT NULL AND o.provider_photo_id = c.provider_photo_id))""",
                reviewId, mediaId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ok", ok);
        after.put("releaseStatus", releaseStatus);
        audit.record(program, user.userId(), "PRIVACY_REVIEWED", "MEDIA", mediaId, reason, Map.of("privacyStatus", status), after);
        return result(mediaId, affected, now, "PENDING");
    }

    private PrivacyResult restart(AuthenticatedUser user, UUID program, UUID mediaId, UUID releaseId, long version) {
        lockJurisdiction(user, mediaId);
        jdbc.queryForList("SELECT id FROM media WHERE id = ? FOR UPDATE", mediaId);
        requireVersion(mediaId, version);
        Optional<Map<String, Object>> latest = latestRelease(mediaId);
        if (latest.isEmpty() || !releaseId.equals(latest.get().get("id")) || !"FAILED".equals(latest.get().get("release_status"))) {
            if (latest.isEmpty()) {
                throw new ApiException(ErrorCode.PRIVACY_RELEASE_CONFLICT, "재요청할 보류 해제 처리가 없습니다. 처리 상태를 확인해 주세요.");
            }
            throw releaseConflict(latest.get());
        }
        jdbc.queryForList("SELECT id FROM privacy_reviews WHERE id = ? FOR UPDATE", releaseId);
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE privacy_reviews SET release_status = 'PENDING', failure_reason = NULL, provider_sync_status = 'PENDING', cycle_attempt_count = 0,
                    next_attempt_at = ?, last_error_code = NULL, version = version + 1 WHERE id = ?""", Timestamp.from(now), releaseId);
        audit.record(program, user.userId(), "PRIVACY_RELEASE_RETRIED", "MEDIA", mediaId, null, Map.of("releaseId", releaseId),
                Map.of("releaseStatus", "PENDING"));
        return result(mediaId, affected(mediaId), now, "PENDING");
    }

    private Map<String, Object> requireVersion(UUID mediaId, long version) {
        Map<String, Object> media = jdbc.queryForMap("""
                SELECT version, privacy_status, provider_image_revision, superseded_by_media_id FROM media WHERE id = ?""", mediaId);
        long current = ((Number) media.get("version")).longValue();
        if (current != version || media.get("superseded_by_media_id") != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("currentVersion", current);
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "사진이 바뀌었습니다. 최신 사진을 다시 확인해 주세요.", details);
        }
        return media;
    }

    private static ApiException releaseConflict(Map<String, Object> latest) {
        return new ApiException(ErrorCode.PRIVACY_RELEASE_CONFLICT, "이미 보류 해제를 처리하고 있습니다. 처리 상태를 확인해 주세요.",
                Map.of("releaseId", latest.get("id"), "status", latest.get("release_status")));
    }

    private List<UUID> affected(UUID mediaId) {
        return jdbc.queryForList(MediaLineage.CTE + """
                SELECT id FROM inspections WHERE media_id IN (SELECT id FROM lineage) OR approved_media_id IN (SELECT id FROM lineage) ORDER BY id""",
                UUID.class, mediaId);
    }

    /** 현재 보류 기간(media.privacy_held_at 이후, 그 보류 전에 끝난 공개는 제외)의 최신 재승인 처리. */
    private Optional<Map<String, Object>> latestRelease(UUID mediaId) {
        return jdbc.queryForList("""
                SELECT r.* FROM privacy_reviews r JOIN media m ON m.id = r.media_id
                WHERE r.media_id = ? AND r.release_status IS NOT NULL AND (m.privacy_held_at IS NULL OR (r.created_at >= m.privacy_held_at
                    AND NOT (r.release_status = 'COMPLETED' AND r.completed_at <= m.privacy_held_at)))
                ORDER BY r.created_at DESC, r.id DESC LIMIT 1""", mediaId).stream().findFirst();
    }

    /** 사진의 현재 개인정보 상태와 처리 상태(관리자 응답 media.privacyRelease와 같은 계산). */
    public PrivacyResult result(UUID mediaId, List<UUID> affected, Instant reviewedAt, String providerSyncStatus) {
        Map<String, Object> media = jdbc.queryForMap("SELECT privacy_status, version FROM media WHERE id = ?", mediaId);
        return new PrivacyResult(mediaId, (String) media.get("privacy_status"), ((Number) media.get("version")).longValue(), providerSyncStatus,
                affected, reviewedAt, release(mediaId).orElse(null));
    }

    public Optional<PrivacyRelease> release(UUID mediaId) {
        return latestRelease(mediaId).map(r -> {
            String status = (String) r.get("release_status");
            boolean waiting = "PENDING".equals(status) && ((Number) r.get("cycle_attempt_count")).intValue() > 0;
            return new PrivacyRelease((UUID) r.get("id"), status, instant(r.get("created_at")), ((Number) r.get("decided_media_version")).intValue(),
                    ((Number) r.get("attempt_count")).intValue(), waiting ? instant(r.get("next_attempt_at")) : null, instant(r.get("completed_at")),
                    (String) r.get("failure_reason"), (String) r.get("invalid_reason"), "FAILED".equals(status));
        });
    }

    private static Instant instant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp t) {
            return t.toInstant();
        }
        return ((OffsetDateTime) v).toInstant();
    }
}
