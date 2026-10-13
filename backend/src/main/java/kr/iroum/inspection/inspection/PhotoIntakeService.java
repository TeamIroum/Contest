package kr.iroum.inspection.inspection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import kr.iroum.inspection.analysis.AnalysisSnapshots;
import kr.iroum.inspection.analysis.AnalyzeOutcome;
import kr.iroum.inspection.analysis.client.AiClient;
import kr.iroum.inspection.analysis.client.AiClient.AiException;
import kr.iroum.inspection.auth.access.AccessPolicy;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.inspection.CaptureResults.CaptureResult;
import kr.iroum.inspection.inspection.CaptureResults.PhotoSubmitResult;
import kr.iroum.inspection.media.MediaStorage;
import kr.iroum.inspection.patrol.PatrolReader.MemberRow;
import kr.iroum.inspection.patrol.PatrolReader.PatrolRow;
import kr.iroum.inspection.patrol.command.PatrolCommandSupport;
import kr.iroum.inspection.review.ReviewTaskOutbox;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사진 접수(업무 API 6.3·7·15절, DB 설계 7.1·7.2·12절, 04단계 설계 S1~S6·S9).
 * <ol>
 * <li>예약(트랜잭션 1): 순회 → 배정 → 장소 → 구도 → 사진 순으로 잠그고 멱등·접수 조건·구도 상태를 확인한 뒤 사진 없는 시도와 분석 작업을 만든다.
 * 구도에 처리 중 포인터(active)를 잡고 접수 리스를 둔다.</li>
 * <li>AI 호출(잠금 없음): 메모리의 사진으로 /analyze를 부르고, 저장된 검사본이 있으면 받아 업무 저장소에 쓴다. 검사 전 바이트는 저장하지 않는다.</li>
 * <li>결과 적용(트랜잭션 2): 같은 순서로 다시 잠그고 리스 토큰이 그대로일 때만 결과를 반영한다. 리스를 잃었으면 저장한 사본을 지우고 현재 상태를 돌려준다.</li>
 * </ol>
 * AI 연결 실패·지연·요청 거절(S1)은 사진을 확보하지 못한 것이므로 RETRY_REQUIRED(503)이며 품질 횟수를 바꾸지 않는다.
 * 저장 없는 실패를 접수 성공처럼 응답하지 않는다. 같은 시도의 재전송은 같은 AI 호출 ID를 써서 AI의 저장 결과 재현을 받는다.
 */
@Service
public class PhotoIntakeService {

    private static final Logger log = LoggerFactory.getLogger(PhotoIntakeService.class);
    private static final String NOT_FOUND = "찾을 수 없거나 접근할 수 없는 항목입니다.";
    private static final int RETRY_AFTER_SECONDS = 30;

    /** 제출 응답: HTTP 상태와 본문. 오류(422)는 결과를 커밋한 뒤 error로 던진다. */
    public record Submitted(HttpStatus status, PhotoSubmitResult body) {
    }

    /** reprocessOf: 서버 보관본 재처리(6.4절)이면 그 보관본 media ID, 새 접수·재전송이면 null. */
    private record Reservation(UUID inspectionId, UUID jobId, String photoId, UUID leaseToken, UUID programId, UUID participantId,
            String locationTags, UUID reprocessOf) {
    }

    /** 예약 단계에서 끝난 경우(멱등 재현·다른 조원 채택 중복). */
    private record Finished(Submitted submitted, ApiException error) {
    }

    private record StoredImage(String key, byte[] bytes, String contentType, int revision) {
    }

    private sealed interface AiOutcome permits Analyzed, Unavailable {
    }

    private record Analyzed(JsonNode result, StoredImage image) implements AiOutcome {
    }

    private record Unavailable(String code) implements AiOutcome {
    }

    private final JdbcTemplate jdbc;
    private final AccessPolicy access;
    private final PatrolCommandSupport support;
    private final CaptureResults results;
    private final ImageInspector images;
    private final AiClient ai;
    private final MediaStorage storage;
    private final ReviewTaskOutbox reviews;
    private final AnalysisSnapshots snapshots;
    private final StopProgress stops;
    private final kr.iroum.inspection.common.idempotency.IdempotencyService idempotency;
    private final java.util.concurrent.Executor executor;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Duration lease;
    private final Duration reanalysisDeadline;
    private final double noIssueSampleRate;

    public PhotoIntakeService(JdbcTemplate jdbc, AccessPolicy access, PatrolCommandSupport support, CaptureResults results,
            ImageInspector images, AiClient ai, MediaStorage storage, ReviewTaskOutbox reviews, AnalysisSnapshots snapshots, StopProgress stops,
            kr.iroum.inspection.common.idempotency.IdempotencyService idempotency,
            @org.springframework.beans.factory.annotation.Qualifier("applicationTaskExecutor") java.util.concurrent.Executor executor, JsonMapper json,
            Clock clock,
            PlatformTransactionManager txManager, @Value("${inspection.photo.ingestion-lease:2m}") Duration lease,
            @Value("${inspection.ai.reanalysis-deadline:30m}") Duration reanalysisDeadline,
            @Value("${inspection.review.no-issue-sample-rate:0}") double noIssueSampleRate) {
        this.jdbc = jdbc;
        this.access = access;
        this.support = support;
        this.results = results;
        this.images = images;
        this.ai = ai;
        this.storage = storage;
        this.reviews = reviews;
        this.snapshots = snapshots;
        this.stops = stops;
        this.idempotency = idempotency;
        this.executor = executor;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.lease = lease;
        this.reanalysisDeadline = reanalysisDeadline;
        this.noIssueSampleRate = noIssueSampleRate;
    }

    public Submitted submit(AuthenticatedUser user, UUID patrolId, UUID stopId, PhotoUpload in, byte[] file) {
        ImageInspector.Checked image = images.inspect(file);
        byte[] fileSha = sha256(image.bytes());
        byte[] requestHash = sha256(json.writeValueAsString(identity(patrolId, stopId, in, fileSha)).getBytes(StandardCharsets.UTF_8));
        Object step = tx.execute(s -> reserve(user, patrolId, stopId, in, image, fileSha, requestHash));
        if (step instanceof Finished f) {
            return finish(f);
        }
        Reservation r = (Reservation) step;
        AiOutcome outcome = callAi(r, image.bytes(), image.contentType(), in.capturedAt(), in.report().toAi(!in.report().boxes().isEmpty()));
        Finished done;
        try {
            done = tx.execute(s -> apply(r, outcome));
        } catch (RuntimeException e) {
            if (outcome instanceof Analyzed a && a.image() != null) {
                storage.deleteQuietly(MediaStorage.BUCKET, a.image().key());
            }
            throw e;
        }
        drainReviews();
        return finish(done);
    }

    /** 사진 시도의 현재 처리 결과(GET /inspections/{id}/capture-status). 그 순회의 시니어만 본다. */
    public CaptureResult status(AuthenticatedUser user, UUID inspectionId) {
        CaptureResults.Context c = results.context(inspectionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND));
        access.requireSeniorParticipant(user, c.patrolId());
        return results.of(c, false);
    }

    /**
     * 접수 리스가 끝난 수신 중 시도의 복구(DB 설계 7.1 "접수 리스·같은 AI ID 결과 조회로 복구", 주기 작업). 새 리스를 잡은 뒤
     * 같은 AI 호출 ID의 저장 결과(GET /results)를 확인해, 검사본이 저장된 결과면 사진을 받아 정상 접수와 같은 규칙으로 반영한다.
     * 결과가 없거나 AI에 닿지 않으면 RETRY_REQUIRED(기기의 같은 ID 재전송으로 복구)로 둔다. 리스가 아직 살아 있으면 아무것도 하지 않는다.
     */
    public boolean recoverExpiredLease(UUID inspectionId) {
        Reservation r = tx.execute(s -> releaseForRecovery(inspectionId));
        if (r == null) {
            return false;
        }
        AiOutcome outcome;
        try {
            Optional<JsonNode> stored = ai.result(r.photoId());
            if (stored.isEmpty() || !AnalyzeOutcome.hasStoredImage(stored.get())) {
                outcome = new Unavailable("LEASE_EXPIRED");
            } else {
                AiClient.MaskedImage masked = ai.maskedImage(r.photoId());
                String key = r.programId() + "/" + r.inspectionId() + "/r" + masked.revision() + "-" + UUID.randomUUID()
                        + ("image/png".equals(masked.contentType()) ? ".png" : ".jpg");
                storage.put(MediaStorage.BUCKET, key, masked.bytes());
                outcome = new Analyzed(stored.get(), new StoredImage(key, masked.bytes(), masked.contentType(), masked.revision()));
            }
        } catch (AiException e) {
            outcome = new Unavailable("LEASE_EXPIRED");
        } catch (java.io.UncheckedIOException e) {
            outcome = new Unavailable("STORAGE_FAILED");
        }
        AiOutcome applied = outcome;
        try {
            tx.execute(s -> apply(r, applied));
        } catch (RuntimeException e) {
            if (applied instanceof Analyzed a && a.image() != null) {
                storage.deleteQuietly(MediaStorage.BUCKET, a.image().key());
            }
            throw e;
        }
        drainReviews();
        return true;
    }

    /** 리스 만료를 잠금 아래 다시 확인하고 복구 작업의 새 리스를 잡는다. 만료가 아니면 null. */
    private Reservation releaseForRecovery(UUID inspectionId) {
        CaptureResults.Context c = results.context(inspectionId).orElse(null);
        if (c == null) {
            return null;
        }
        PatrolRow patrol = support.lock(c.patrolId());
        lockStopAndSlot(c.stopId(), c.slotId());
        lockInspection(inspectionId);
        Instant now = clock.instant();
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT (receipt_status = 'RECEIVING' OR (receipt_status = 'RECEIVED' AND capture_status = 'PROCESSING'))
                    AND ingestion_lease_until <= ? AS expired,
                    CASE WHEN receipt_status = 'RECEIVED' THEN COALESCE(approved_media_id, media_id) END AS reprocess
                FROM inspections WHERE id = ?""", Timestamp.from(now), inspectionId);
        if (!Boolean.TRUE.equals(row.get("expired"))) {
            return null;
        }
        UUID token = UUID.randomUUID();
        jdbc.update("UPDATE inspections SET ingestion_lease_token = ?, ingestion_lease_until = ?, updated_at = ? WHERE id = ?", token,
                Timestamp.from(now.plus(lease)), Timestamp.from(now), inspectionId);
        Map<String, Object> job = jdbc.queryForMap("""
                SELECT id, provider_photo_id FROM analysis_jobs WHERE inspection_id = ? AND type = 'ANALYZE'
                ORDER BY input_version DESC LIMIT 1""", inspectionId);
        jdbc.update("UPDATE analysis_jobs SET lease_token = ?, locked_until = ?, updated_at = ? WHERE id = ? AND status = 'PROCESSING'", token,
                Timestamp.from(now.plus(lease)), Timestamp.from(now), job.get("id"));
        return new Reservation(inspectionId, (UUID) job.get("id"), (String) job.get("provider_photo_id"), token, patrol.programId(), null, "",
                (UUID) row.get("reprocess"));
    }

    /**
     * 기술 재처리(업무 API 6.4절, POST /inspections/{id}/processing-retries). 서버 보관본(RECEIVED)이 있는 처리 실패(TECHNICAL_FAILED)만
     * 다시 판독한다. 같은 inspection에 새 작업 세대(input_version)·새 AI 호출 ID를 만들고, 구도 잠금 아래 처리 중 포인터를 다시 잡는다.
     * <ul>
     * <li>사진을 확보하지 못한 시도(RETRY_REQUIRED·리스 만료)는 409 PHOTO_RESUBMISSION_REQUIRED(같은 ID·파일 재전송).</li>
     * <li>검토로 인계된 구도는 409 SLOT_RESOLVED. 다른 시도가 채택됐거나 처리 중이면 이 시도를 되살리지 않고 현재 상태를 돌려준다.</li>
     * <li>AI 재판독 대기(retry.pending)·처리 중·이미 끝난 결과도 다시 실행하지 않고 현재 상태를 돌려준다.</li>
     * </ul>
     * 202 응답은 접수 시점 상태(PROCESSING·WAIT)이며 판독은 커밋 뒤 비동기로 진행한다. 결과는 capture-status로 확인한다.
     * Idempotency-Key: 같은 키·같은 대상은 최초 응답을 재현한다.
     */
    public CaptureResult retryProcessing(AuthenticatedUser user, UUID inspectionId, UUID key) {
        java.util.concurrent.atomic.AtomicReference<Reservation> started = new java.util.concurrent.atomic.AtomicReference<>();
        CaptureResult body = tx.execute(s -> {
            CaptureResults.Context c = results.context(inspectionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND));
            AccessPolicy.SeniorAssignment me = access.requireSeniorParticipant(user, c.patrolId());
            return idempotency.execute(user.userId(), "POST /inspections/{inspectionId}/processing-retries", key, Map.of("inspectionId", inspectionId),
                    CaptureResult.class, 202, () -> reserveReprocess(c, me, started)).body();
        });
        Reservation r = started.get();
        if (r != null) {
            executor.execute(() -> runReprocess(r));
        }
        return body;
    }

    private CaptureResult reserveReprocess(CaptureResults.Context c, AccessPolicy.SeniorAssignment me,
            java.util.concurrent.atomic.AtomicReference<Reservation> started) {
        PatrolRow patrol = support.lock(c.patrolId());
        SlotState slot = lockStopAndSlot(c.stopId(), c.slotId());
        lockInspection(c.inspectionId());
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT client_submission_id, receipt_status, capture_status, ingestion_lease_until, COALESCE(approved_media_id, media_id) AS media,
                    captured_at, device_mask_report_json::text AS report FROM inspections WHERE id = ?""", c.inspectionId());
        String receipt = (String) row.get("receipt_status");
        Instant leaseUntil = row.get("ingestion_lease_until") == null ? null : ((Timestamp) row.get("ingestion_lease_until")).toInstant();
        boolean expired = "RECEIVING".equals(receipt) && leaseUntil != null && !leaseUntil.isAfter(clock.instant());
        if ("RETRY_REQUIRED".equals(receipt) || expired) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("inspectionId", c.inspectionId());
            details.put("clientSubmissionId", row.get("client_submission_id"));
            details.put("receiptStatus", receipt);
            throw new ApiException(ErrorCode.PHOTO_RESUBMISSION_REQUIRED, "사진을 다시 보내야 해요.", details);
        }
        if (!"RECEIVED".equals(receipt) || !"TECHNICAL_FAILED".equals(row.get("capture_status"))) {
            return results.of(c.inspectionId());
        }
        if ("REVIEW_REQUESTED".equals(slot.resolution())) {
            throw slotResolved(slot);
        }
        if (slot.acceptedInspectionId() != null || (slot.activeInspectionId() != null && !expireIfStale(slot.activeInspectionId()))) {
            return results.of(c.inspectionId());
        }
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        UUID media = (UUID) row.get("media");
        byte[] stored = jdbc.queryForObject("SELECT sha256 FROM media WHERE id = ?", byte[].class, media);
        jdbc.update("""
                UPDATE inspections SET capture_status = 'PROCESSING', ingestion_lease_token = ?, ingestion_lease_until = ?, updated_at = ?
                WHERE id = ?""", token, Timestamp.from(now.plus(lease)), Timestamp.from(now), c.inspectionId());
        UUID jobId = UUID.randomUUID();
        String photoId = "insp_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO analysis_jobs (id, program_id, inspection_id, type, status, retry_owner, provider_photo_id, input_hash, attempt_count,
                    locked_until, lease_token, input_version, started_at, created_at, updated_at)
                VALUES (?, ?, ?, 'ANALYZE', 'PROCESSING', 'BACKEND', ?, ?, 1, ?, ?,
                    (SELECT max(input_version) + 1 FROM analysis_jobs WHERE inspection_id = ? AND type = 'ANALYZE'), ?, ?, ?)""",
                jobId, patrol.programId(), c.inspectionId(), photoId, sha256((HexFormat.of().formatHex(stored) + "|" + row.get("report") + "|"
                        + row.get("captured_at")).getBytes(StandardCharsets.UTF_8)), Timestamp.from(now.plus(lease)), token, c.inspectionId(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                UPDATE capture_slots SET active_inspection_id = ?, version = version + 1, updated_at = ? WHERE id = ?""", c.inspectionId(),
                Timestamp.from(now), slot.id());
        support.bumpRevision(patrol.id());
        String tags = jdbc.queryForObject("""
                SELECT COALESCE(string_agg(t, ',' ORDER BY ord), '') FROM patrol_stops ps JOIN facilities f ON f.id = ps.facility_id,
                    jsonb_array_elements_text(f.location_tags_json) WITH ORDINALITY AS x(t, ord) WHERE ps.id = ?""", String.class, c.stopId());
        UUID participant = jdbc.queryForObject("""
                SELECT m.participant_id FROM inspections i JOIN patrol_members m ON m.patrol_id = ? AND m.user_id_snapshot = i.submitted_by
                WHERE i.id = ?""", UUID.class, patrol.id(), c.inspectionId());
        started.set(new Reservation(c.inspectionId(), jobId, photoId, token, patrol.programId(), participant, tags == null ? "" : tags, media));
        return results.of(c.inspectionId());
    }

    /** 커밋 뒤 비동기 재판독. 서버 보관본(검사 완료 사진)을 같은 앱 보고와 함께 다시 보낸다. 실패하면 TECHNICAL_FAILED로 남긴다. */
    void runReprocess(Reservation r) {
        AiOutcome outcome;
        try {
            Map<String, Object> m = jdbc.queryForMap("""
                    SELECT md.object_key, md.content_type, i.captured_at, i.device_mask_report_json::text AS report
                    FROM inspections i JOIN media md ON md.id = ? WHERE i.id = ?""", r.reprocessOf(), r.inspectionId());
            byte[] bytes = storage.read(MediaStorage.BUCKET, (String) m.get("object_key"));
            JsonNode stored = json.readTree((String) m.get("report"));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("faces", stored.path("faces").asInt());
            report.put("plates", stored.path("plates").asInt());
            report.put("vehicles", stored.path("vehicles").asInt());
            report.put("detector", stored.path("detector").asString(""));
            report.put("app_version", stored.path("appVersion").asString(""));
            outcome = callAi(r, bytes, (String) m.get("content_type"), ((Timestamp) m.get("captured_at")).toInstant(), report);
        } catch (RuntimeException e) {
            log.warn("보관본 재처리 준비 실패: {}", e.getClass().getSimpleName());
            outcome = new Unavailable("REPROCESS_FAILED");
        }
        AiOutcome applied = outcome;
        try {
            tx.execute(s -> apply(r, applied));
        } catch (RuntimeException e) {
            if (applied instanceof Analyzed a && a.image() != null) {
                storage.deleteQuietly(MediaStorage.BUCKET, a.image().key());
            }
            log.warn("보관본 재처리 결과 반영 실패(리스 만료 뒤 복구 작업이 정리): {}", e.getClass().getSimpleName());
            return;
        }
        drainReviews();
    }

    private Submitted finish(Finished f) {
        if (f.error() != null) {
            throw f.error();
        }
        return f.submitted();
    }

    private void drainReviews() {
        try {
            reviews.drain();
        } catch (RuntimeException e) {
            log.warn("검토 작업 생성은 주기 작업으로 넘김: {}", e.getClass().getSimpleName());
        }
    }

    /** 멱등 해시 대상: 경로·구도·시도·촬영 시각·마스킹 정보·위치·파일 해시. assignmentRef는 뺀다(설계안 4.8). */
    private Map<String, Object> identity(UUID patrolId, UUID stopId, PhotoUpload in, byte[] fileSha) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patrolId", patrolId);
        m.put("stopId", stopId);
        m.put("captureSlotId", in.captureSlotId());
        m.put("attemptId", in.attemptId());
        m.put("capturedAt", in.capturedAt().toString());
        m.put("maskingVersion", in.maskingVersion());
        m.put("deviceMaskReport", in.report().toAi(true));
        m.put("latitude", in.latitude() == null ? null : in.latitude().stripTrailingZeros().toPlainString());
        m.put("longitude", in.longitude() == null ? null : in.longitude().stripTrailingZeros().toPlainString());
        m.put("accuracy", in.accuracy() == null ? null : in.accuracy().stripTrailingZeros().toPlainString());
        m.put("file", HexFormat.of().formatHex(fileSha));
        return m;
    }

    // ---------------------------------------------------------------- 예약

    private record Existing(UUID id, UUID slotId, byte[] requestHash, String receiptStatus, Instant leaseUntil, String captureStatus,
            String retakeReason) {
    }

    private Object reserve(AuthenticatedUser user, UUID patrolId, UUID stopId, PhotoUpload in, ImageInspector.Checked image, byte[] fileSha,
            byte[] requestHash) {
        AccessPolicy.SeniorAssignment me = access.requireSeniorParticipant(user, patrolId);
        PatrolRow patrol = support.lock(patrolId);
        UUID slotStop = jdbc.query("""
                SELECT s.patrol_stop_id FROM capture_slots s JOIN patrol_stops ps ON ps.id = s.patrol_stop_id
                WHERE s.id = ? AND ps.patrol_id = ?""", (rs, i) -> rs.getObject(1, UUID.class), in.captureSlotId(), patrolId)
                .stream().findFirst().orElse(null);
        if (slotStop == null || !slotStop.equals(stopId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND);
        }
        Optional<Existing> existing = existing("client_submission_id", user.userId(), in.clientSubmissionId());
        if (existing.isPresent()) {
            Existing e = existing.get();
            if (!MessageDigest.isEqual(e.requestHash(), requestHash) || !e.slotId().equals(in.captureSlotId())
                    || (in.assignmentRef() != null && !in.assignmentRef().equals(me.memberId()))) {
                throw submissionConflict(e.id(), in.clientSubmissionId());
            }
            lockStopAndSlot(stopId, e.slotId());
            lockInspection(e.id());
            Existing now = existing("client_submission_id", user.userId(), in.clientSubmissionId()).orElseThrow();
            if ("RECEIVING".equals(now.receiptStatus())) {
                if (now.leaseUntil() != null && now.leaseUntil().isAfter(clock.instant())) {
                    throw new ApiException(ErrorCode.SUBMISSION_PROCESSING, "이 사진을 받고 있어요. 잠시 기다려 주세요.",
                            Map.of("existingInspectionId", now.id(), "clientSubmissionId", in.clientSubmissionId()));
                }
                markRetryRequired(now.id(), "LEASE_EXPIRED");
            } else if (!"RETRY_REQUIRED".equals(now.receiptStatus())) {
                return replay(now);
            }
            // 사진을 확보하지 못한 시도의 같은 파일 재전송: 같은 시도·같은 AI 호출 ID로 다시 예약한다.
            return admit(user, patrol, me, in, slotStop, now.id(), null, null);
        }
        Optional<Existing> sameAttempt = existing("attempt_id", user.userId(), in.attemptId());
        if (sameAttempt.isPresent()) {
            throw submissionConflict(sameAttempt.get().id(), in.clientSubmissionId());
        }
        return admit(user, patrol, me, in, slotStop, null, image, new byte[][] {fileSha, requestHash});
    }

    /**
     * 접수 조건과 구도 상태를 확인하고 예약한다. retryOf가 있으면 그 시도를 다시 예약하고, 없으면 새 시도를 만든다.
     * 조건(DB 설계 7.1-1, 설계안 7절): 취소 아님, 서버 시각 &lt; 전송 기한, 촬영 시각이 활동일·미래 상한 안, 촬영 시각의 참여 구간,
     * 실제 복귀 뒤 촬영 아님(D-27), 혼자 남은 대기 구간 밖(D-35).
     */
    private Object admit(AuthenticatedUser user, PatrolRow patrol, AccessPolicy.SeniorAssignment me, PhotoUpload in, UUID stopId,
            UUID retryOf, ImageInspector.Checked image, byte[][] hashes) {
        support.requireNotCancelled(patrol);
        if (!clock.instant().isBefore(patrol.syncDeadlineAt())) {
            throw new ApiException(ErrorCode.SYNC_DEADLINE_EXCEEDED, "오늘 사진 전송 기한이 지났습니다. 담당자에게 문의해 주세요.",
                    Map.of("syncDeadlineAt", patrol.syncDeadlineAt().toString()));
        }
        support.requireActivityTime(patrol, in.capturedAt());
        UUID participation = support.participationAt(patrol, me, in.assignmentRef(), in.capturedAt());
        MemberRow sender = support.memberRow(patrol.id(), me.participantId());
        if (sender.returnedAt() != null && in.capturedAt().isAfter(sender.returnedAt())) {
            throw new ApiException(ErrorCode.PHOTO_AFTER_RETURN, "복귀 이후에 촬영한 사진은 접수할 수 없습니다.");
        }
        if (support.waitingAt(patrol.id(), me.participantId(), in.capturedAt())) {
            throw support.stateConflict("SOLO_WAITING", patrol, "혼자 남아 담당자 안내를 기다리는 중이에요. 새 점검은 멈춰 주세요.");
        }
        SlotState slot = lockStopAndSlot(stopId, in.captureSlotId());
        if (slot.activeInspectionId() != null && !slot.activeInspectionId().equals(retryOf)) {
            if (!expireIfStale(slot.activeInspectionId())) {
                throw new ApiException(ErrorCode.SLOT_PROCESSING, "같이 계신 분의 사진을 처리하고 있어요. 잠시 기다려 주세요.",
                        Map.of("existingInspectionId", slot.activeInspectionId(), "captureSlotId", slot.id()));
            }
            slot = lockStopAndSlot(stopId, in.captureSlotId());
        }
        if (slot.acceptedInspectionId() != null) {
            // D-18: 다른 조원 사진이 이미 채택됐으면 채택된 기존 시도 내용을 duplicate=true로 돌려준다. 새 시도·AI 호출은 만들지 않는다.
            CaptureResults.Context accepted = results.context(slot.acceptedInspectionId()).orElseThrow();
            CaptureResult shown = results.of(accepted, true);
            CaptureResult asMine = new CaptureResult(shown.inspectionId(), in.clientSubmissionId(), shown.attemptNo(), shown.receiptStatus(),
                    shown.captureStatus(), shown.nextAction(), shown.retakeReason(), shown.retakesRemaining(), shown.message(),
                    shown.stopId(), shown.captureSlotId(), shown.nextStopId(), shown.nextCaptureSlotId(), shown.patrolRevision(),
                    shown.qualityFailureCount());
            return new Finished(new Submitted(HttpStatus.OK, asMine.submitted(true)), null);
        }
        if ("REVIEW_REQUESTED".equals(slot.resolution())) {
            throw slotResolved(slot);
        }
        if ("SKIPPED".equals(slot.resolution())) {
            // D-39: 그 구도를 SKIPPED로 만든 건너뛰기의 발생 시각보다 먼저 촬영한 사진만 받는다. 도착 순서는 쓰지 않는다.
            Instant skippedAt = slot.skippedByEventId() == null ? null
                    : jdbc.queryForObject("SELECT occurred_at FROM patrol_events WHERE id = ?", java.time.OffsetDateTime.class,
                            slot.skippedByEventId()).toInstant();
            if (skippedAt == null || !in.capturedAt().isBefore(skippedAt)) {
                throw slotResolved(slot);
            }
        }
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        UUID inspectionId;
        UUID jobId;
        String photoId;
        if (retryOf != null) {
            inspectionId = retryOf;
            jdbc.update("""
                    UPDATE inspections SET receipt_status = 'RECEIVING', capture_status = 'PROCESSING', ingestion_lease_token = ?,
                        ingestion_lease_until = ?, updated_at = ? WHERE id = ?""", token, Timestamp.from(now.plus(lease)), Timestamp.from(now),
                    inspectionId);
            Map<String, Object> job = jdbc.queryForMap("""
                    SELECT id, provider_photo_id FROM analysis_jobs WHERE inspection_id = ? AND type = 'ANALYZE'
                    ORDER BY input_version DESC LIMIT 1""", inspectionId);
            jobId = (UUID) job.get("id");
            photoId = (String) job.get("provider_photo_id");
            jdbc.update("""
                    UPDATE analysis_jobs SET status = 'PROCESSING', lease_token = ?, locked_until = ?, attempt_count = attempt_count + 1,
                        error_code = NULL, finished_at = NULL, updated_at = ? WHERE id = ?""",
                    token, Timestamp.from(now.plus(lease)), Timestamp.from(now), jobId);
        } else {
            inspectionId = UUID.randomUUID();
            int attemptNo = jdbc.queryForObject("SELECT COALESCE(max(attempt_no), 0) + 1 FROM inspections WHERE capture_slot_id = ?",
                    Integer.class, slot.id());
            jdbc.update("""
                    INSERT INTO inspections (id, program_id, capture_slot_id, submitted_by, client_submission_id, attempt_id, request_hash,
                        submission_sha256, attempt_no, captured_at, submitted_at, receipt_status, ingestion_lease_token, ingestion_lease_until,
                        masking_version, device_masked, device_mask_report_json, capture_status, latitude, longitude, location_accuracy_meters,
                        participation_id, assignment_ref, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVING', ?, ?, ?, true, ?::jsonb, 'PROCESSING', ?, ?, ?, ?, ?, ?, ?)""",
                    inspectionId, patrol.programId(), slot.id(), user.userId(), in.clientSubmissionId(), in.attemptId(), hashes[1], hashes[0],
                    attemptNo, Timestamp.from(in.capturedAt()), Timestamp.from(now), token, Timestamp.from(now.plus(lease)),
                    in.maskingVersion(), json.writeValueAsString(storedReport(in.report())), in.latitude(), in.longitude(), in.accuracy(),
                    participation, in.assignmentRef(), Timestamp.from(now), Timestamp.from(now));
            jobId = UUID.randomUUID();
            photoId = "insp_" + UUID.randomUUID().toString().replace("-", "");
            byte[] inputHash = sha256((HexFormat.of().formatHex(hashes[0]) + "|" + json.writeValueAsString(in.report().toAi(true)) + "|"
                    + in.capturedAt() + "|" + me.participantId()).getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                    INSERT INTO analysis_jobs (id, program_id, inspection_id, type, status, retry_owner, provider_photo_id, input_hash,
                        attempt_count, locked_until, lease_token, input_version, started_at, created_at, updated_at)
                    VALUES (?, ?, ?, 'ANALYZE', 'PROCESSING', 'BACKEND', ?, ?, 1, ?, ?, 1, ?, ?, ?)""",
                    jobId, patrol.programId(), inspectionId, photoId, inputHash, Timestamp.from(now.plus(lease)), token, Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now));
        }
        // 처리 중 포인터. D-39로 건너뛴 구도를 받는 동안은 OPEN으로 두고 기준 건너뛰기 연결은 남긴다(V14).
        jdbc.update("""
                UPDATE capture_slots SET active_inspection_id = ?, resolution = 'OPEN', version = version + 1, updated_at = ? WHERE id = ?""",
                inspectionId, Timestamp.from(now), slot.id());
        support.bumpRevision(patrol.id());
        String tags = jdbc.queryForObject("""
                SELECT COALESCE(string_agg(t, ',' ORDER BY ord), '') FROM patrol_stops ps JOIN facilities f ON f.id = ps.facility_id,
                    jsonb_array_elements_text(f.location_tags_json) WITH ORDINALITY AS x(t, ord) WHERE ps.id = ?""", String.class, stopId);
        return new Reservation(inspectionId, jobId, photoId, token, patrol.programId(), me.participantId(), tags == null ? "" : tags, null);
    }

    /** 업무 저장용 앱 보고: 개수·검출기·앱 버전과 가린 위치 개수(위치 목록은 저장하지 않음). */
    private static Map<String, Object> storedReport(DeviceMaskReport report) {
        Map<String, Object> out = new LinkedHashMap<>(report.toStored(false));
        if (!report.boxes().isEmpty()) {
            out.put("boxCount", report.boxes().size());
        }
        return out;
    }

    private Optional<Existing> existing(String column, UUID user, UUID id) {
        return jdbc.query("SELECT id, capture_slot_id, request_hash, receipt_status, ingestion_lease_until, capture_status, retake_reason "
                + "FROM inspections WHERE submitted_by = ? AND " + column + " = ?",
                (rs, i) -> new Existing(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBytes(3), rs.getString(4),
                        ts(rs.getObject(5, java.time.OffsetDateTime.class)), rs.getString(6), rs.getString(7)),
                user, id).stream().findFirst();
    }

    /** 같은 제출의 재현. 처리 실패(422)였으면 같은 오류를, 아니면 현재 결과를 duplicate=true로 돌려준다. */
    private Finished replay(Existing e) {
        CaptureResults.Context c = results.context(e.id()).orElseThrow();
        CaptureResult r = results.of(c, false);
        if ("NO_MEDIA".equals(c.receiptStatus()) && c.retakeReason() == null) {
            return new Finished(null, imageProcessingFailed(r));
        }
        if ("RETRY_REQUIRED".equals(c.receiptStatus())) {
            return new Finished(null, analysisUnavailable(e.id()));
        }
        return new Finished(new Submitted(httpStatus(c), r.submitted(true)), null);
    }

    private HttpStatus httpStatus(CaptureResults.Context c) {
        return c.aiPending() ? HttpStatus.ACCEPTED : HttpStatus.OK;
    }

    private record SlotState(UUID id, String resolution, UUID activeInspectionId, UUID acceptedInspectionId, UUID skippedByEventId,
            int qualityFailureCount) {
    }

    /** 잠금 단계 9 → 10. */
    private SlotState lockStopAndSlot(UUID stopId, UUID slotId) {
        jdbc.queryForList("SELECT id FROM patrol_stops WHERE id = ? FOR UPDATE", stopId);
        return jdbc.query("""
                SELECT id, resolution, active_inspection_id, accepted_inspection_id, skipped_by_event_id, quality_failure_count
                FROM capture_slots WHERE id = ? FOR UPDATE""",
                (rs, i) -> new SlotState(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getInt(6)),
                slotId).get(0);
    }

    private void lockInspection(UUID inspectionId) {
        jdbc.queryForList("SELECT id FROM inspections WHERE id = ? FOR UPDATE", inspectionId);
    }

    /** 리스가 끝난 수신 중 시도는 사진을 확보하지 못한 것으로 본다(같은 ID 재전송으로 복구). 바꿨으면 true. */
    private boolean expireIfStale(UUID inspectionId) {
        lockInspection(inspectionId);
        Boolean stale = jdbc.queryForObject("""
                SELECT receipt_status = 'RECEIVING' AND ingestion_lease_until <= ? FROM inspections WHERE id = ?""", Boolean.class,
                Timestamp.from(clock.instant()), inspectionId);
        if (Boolean.TRUE.equals(stale)) {
            markRetryRequired(inspectionId, "LEASE_EXPIRED");
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- AI 호출(잠금 없음)

    private AiOutcome callAi(Reservation r, byte[] bytes, String contentType, Instant capturedAt, Map<String, Object> report) {
        try {
            JsonNode result = ai.analyze(new AiClient.AnalyzeCall(r.photoId(), bytes, contentType, r.participantId().toString(), capturedAt,
                    report, r.locationTags()));
            String status = AnalyzeOutcome.status(result);
            boolean keep = AnalyzeOutcome.hasStoredImage(result) && !"error".equals(status);
            if (!keep) {
                return new Analyzed(result, null);
            }
            // 서버 개인정보 검사를 마친 사본만 업무 저장소에 쓴다.
            AiClient.MaskedImage masked = ai.maskedImage(r.photoId());
            String key = r.programId() + "/" + r.inspectionId() + "/r" + masked.revision() + "-" + UUID.randomUUID()
                    + ("image/png".equals(masked.contentType()) ? ".png" : ".jpg");
            storage.put(MediaStorage.BUCKET, key, masked.bytes());
            return new Analyzed(result, new StoredImage(key, masked.bytes(), masked.contentType(), masked.revision()));
        } catch (AiException e) {
            // S1: 연결 실패·지연·요청 거절 모두 사진 미확보. AI 오류 code는 내부 기록·경보용이며 응답에 넣지 않는다.
            if (!e.transientFailure()) {
                log.error("AI 요청 거절(어댑터 확인 필요): HTTP {} code={}", e.status(), e.code());
            }
            return new Unavailable(e.code() != null ? e.code() : e.transientFailure() ? "AI_UNAVAILABLE" : "AI_REJECTED");
        } catch (java.io.UncheckedIOException e) {
            log.error("검사 완료 사진 저장 실패: {}", e.getCause().getClass().getSimpleName());
            return new Unavailable("STORAGE_FAILED");
        }
    }

    // ---------------------------------------------------------------- 결과 적용

    private Finished apply(Reservation r, AiOutcome outcome) {
        CaptureResults.Context before = results.context(r.inspectionId()).orElseThrow();
        PatrolRow patrol = support.lock(before.patrolId());
        SlotState slot = lockStopAndSlot(before.stopId(), before.slotId());
        lockInspection(r.inspectionId());
        UUID lease = jdbc.query("SELECT ingestion_lease_token FROM inspections WHERE id = ? AND receipt_status IN ('RECEIVING', 'RECEIVED')",
                (rs, i) -> rs.getObject(1, UUID.class), r.inspectionId()).stream().findFirst().orElse(null);
        if (!r.leaseToken().equals(lease)) {
            // 리스를 잃었다(만료 뒤 다른 처리). 이 결과는 버리고 현재 상태를 알려 준다.
            if (outcome instanceof Analyzed a && a.image() != null) {
                storage.deleteQuietly(MediaStorage.BUCKET, a.image().key());
            }
            return replay(existingOf(r.inspectionId()));
        }
        if (outcome instanceof Unavailable u) {
            return r.reprocessOf() != null ? reprocessFailed(r, u.code()) : retryRequired(r, u.code());
        }
        Analyzed a = (Analyzed) outcome;
        JsonNode result = a.result();
        String status = AnalyzeOutcome.status(result);
        boolean storedNeeded = "ok".equals(status) || "needs_review".equals(status);
        if (!List.of("ok", "needs_review", "retake", "error").contains(status) || (storedNeeded && a.image() == null)) {
            log.error("AI 판독 응답 형식 이상: status={} stored={}", status, a.image() != null);
            if (a.image() != null) {
                storage.deleteQuietly(MediaStorage.BUCKET, a.image().key());
            }
            return r.reprocessOf() != null ? reprocessFailed(r, "INVALID_RESPONSE") : retryRequired(r, "INVALID_RESPONSE");
        }
        Instant now = clock.instant();
        MemberRow sender = senderOf(patrol, r.inspectionId());
        boolean late = patrol.ended() || "RETURNING".equals(patrol.status()) || sender == null || sender.returned();
        // S4: D-39로 건너뛴 구도에서 받은 사진은 건너뛰기 뒤 새 촬영을 안내할 수 없으므로 실패 시 검토로 넘긴다.
        boolean noRetake = late || slot.skippedByEventId() != null;
        UUID mediaId = a.image() == null ? null : insertMedia(r, a.image(), result, now);
        String ledgerType = jdbc.queryForObject("SELECT type_snapshot FROM patrol_stops WHERE id = ?", String.class, before.stopId());
        List<String> triggers = List.of();
        String capture;
        String resolution = null;
        String retakeReason = null;
        ApiException error = null;
        switch (status) {
            case "ok" -> {
                capture = "ACCEPTED";
                resolution = "ACCEPTED";
                triggers = AnalyzeOutcome.reviewTriggers(result, ledgerType, sampleNoIssue());
            }
            case "needs_review" -> {
                capture = "REVIEW_REQUESTED";
                resolution = "REVIEW_REQUESTED";
                triggers = AnalyzeOutcome.reviewTriggers(result, ledgerType, false);
            }
            case "retake" -> {
                retakeReason = AnalyzeOutcome.retakeReason(result);
                int count = jdbc.queryForObject("""
                        UPDATE capture_slots SET quality_failure_count = LEAST(3, quality_failure_count + 1) WHERE id = ?
                        RETURNING quality_failure_count""", Integer.class, slot.id());
                if (count >= 3 || noRetake) {
                    capture = "REVIEW_REQUESTED";
                    resolution = "REVIEW_REQUESTED";
                    triggers = List.of("QUALITY_RETRY_EXHAUSTED");
                } else {
                    capture = "RETAKE_REQUIRED";
                }
            }
            default -> {
                // AI가 사진을 처리하지 못함(HTTP 200 + error). 저장 없음, 품질 횟수 차감 없음.
                if (noRetake) {
                    capture = "REVIEW_REQUESTED";
                    resolution = "REVIEW_REQUESTED";
                    triggers = List.of("TECHNICAL_FAILURE");
                } else {
                    capture = "TECHNICAL_FAILED";
                }
            }
        }
        String receipt = mediaId != null ? "RECEIVED" : "NO_MEDIA";
        if (r.reprocessOf() != null) {
            // 보관본 재처리: 접수 사실(media_id·received_at)은 그대로, 새 검사본이 있으면 승인 포인터만 새 사본으로 옮긴다.
            jdbc.update("""
                    UPDATE inspections SET capture_status = ?, retake_reason = ?, quality_failure_applied_at = ?,
                        approved_media_id = COALESCE(?, approved_media_id, media_id), ingestion_lease_token = NULL, ingestion_lease_until = NULL,
                        updated_at = ? WHERE id = ?""",
                    capture, retakeReason,
                    retakeReason == null ? null : Timestamp.from(now), mediaId, Timestamp.from(now), r.inspectionId());
            if (mediaId != null) {
                jdbc.update("UPDATE media SET superseded_by_media_id = ?, version = version + 1, updated_at = ? WHERE id = ?", mediaId,
                        Timestamp.from(now), r.reprocessOf());
            }
        } else jdbc.update("""
                UPDATE inspections SET receipt_status = ?, capture_status = ?, retake_reason = ?, quality_failure_applied_at = ?,
                    media_id = ?, approved_media_id = ?, received_at = ?, ingestion_lease_token = NULL, ingestion_lease_until = NULL,
                    updated_at = ? WHERE id = ?""",
                receipt, capture, retakeReason, retakeReason == null ? null : Timestamp.from(now), mediaId, mediaId,
                mediaId == null ? null : Timestamp.from(now), Timestamp.from(now), r.inspectionId());
        boolean aiPending = "needs_review".equals(status) && AnalyzeOutcome.retryPending(result);
        recordSnapshot(r, result, mediaId, now, aiPending);
        if ("ACCEPTED".equals(resolution)) {
            jdbc.update("""
                    UPDATE capture_slots SET accepted_inspection_id = ?, active_inspection_id = NULL, resolution = 'ACCEPTED',
                        version = version + 1, updated_at = ? WHERE id = ?""", r.inspectionId(), Timestamp.from(now), slot.id());
        } else {
            String next = resolution != null ? resolution : slot.skippedByEventId() != null ? "SKIPPED" : "OPEN";
            jdbc.update("""
                    UPDATE capture_slots SET active_inspection_id = NULL, resolution = ?, version = version + 1, updated_at = ? WHERE id = ?""",
                    next, Timestamp.from(now), slot.id());
        }
        stops.settle(before.stopId());
        support.bumpRevision(patrol.id());
        for (String trigger : triggers) {
            reviews.enqueue(r.programId(), r.inspectionId(), before.stopId(), slot.id(), trigger);
        }
        CaptureResults.Context after = results.context(r.inspectionId()).orElseThrow();
        CaptureResult shown = results.of(after, false);
        if (r.reprocessOf() != null) {
            return new Finished(new Submitted(HttpStatus.ACCEPTED, shown.submitted(false)), null);
        }
        if ("error".equals(status)) {
            error = imageProcessingFailed(shown);
            return new Finished(null, error);
        }
        return new Finished(new Submitted(httpStatus(after), shown.submitted(false)), null);
    }

    private Existing existingOf(UUID inspectionId) {
        return jdbc.query("SELECT id, capture_slot_id, request_hash, receipt_status, ingestion_lease_until, capture_status, retake_reason "
                + "FROM inspections WHERE id = ?",
                (rs, i) -> new Existing(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBytes(3), rs.getString(4),
                        ts(rs.getObject(5, java.time.OffsetDateTime.class)), rs.getString(6), rs.getString(7)),
                inspectionId).get(0);
    }

    /** 제출자의 이 조 배정 행(이동해 나갔으면 null: 늦은 사진으로 본다). */
    private MemberRow senderOf(PatrolRow patrol, UUID inspectionId) {
        UUID user = jdbc.queryForObject("SELECT submitted_by FROM inspections WHERE id = ?", UUID.class, inspectionId);
        return support.members(patrol.id()).stream().filter(m -> m.userId().equals(user)).findFirst().orElse(null);
    }

    private boolean sampleNoIssue() {
        return noIssueSampleRate > 0 && ThreadLocalRandom.current().nextDouble() < noIssueSampleRate;
    }

    private UUID insertMedia(Reservation r, StoredImage image, JsonNode result, Instant now) {
        UUID uploader = jdbc.queryForObject("SELECT submitted_by FROM inspections WHERE id = ?", UUID.class, r.inspectionId());
        JsonNode privacy = AnalyzeOutcome.privacyReport(result);
        return jdbc.queryForObject("""
                INSERT INTO media (program_id, uploaded_by, bucket, object_key, purpose, privacy_status, content_type, size_bytes, sha256,
                    provider_photo_id, provider_image_revision, privacy_report_json, source_media_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'INSPECTION', 'APPROVED', ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING id""", UUID.class,
                r.programId(), uploader, MediaStorage.BUCKET, image.key(), image.contentType(), (long) image.bytes().length,
                sha256(image.bytes()), r.photoId(), image.revision(), privacy == null ? null : json.writeValueAsString(privacy),
                r.reprocessOf(), Timestamp.from(now), Timestamp.from(now));
    }

    /** AI 원문 스냅샷(사진 주소 제외)을 남기고 작업을 끝낸다. AI가 재판독 중이면 추적 기한까지 작업을 열어 둔다(G5). */
    private void recordSnapshot(Reservation r, JsonNode result, UUID mediaId, Instant now, boolean aiPending) {
        snapshots.append(r.programId(), r.jobId(), result, mediaId, now);
        String model = AnalysisSnapshots.text(result.path("meta"), "model");
        if (aiPending) {
            jdbc.update("""
                    UPDATE analysis_jobs SET status = 'PROCESSING', retry_owner = 'AI', lease_token = NULL, locked_until = NULL, next_run_at = ?,
                        model_version = ?, updated_at = ? WHERE id = ?""",
                    Timestamp.from(now.plus(reanalysisDeadline)), model, Timestamp.from(now), r.jobId());
        } else {
            jdbc.update("""
                    UPDATE analysis_jobs SET status = 'SUCCEEDED', lease_token = NULL, locked_until = NULL, model_version = ?, finished_at = ?,
                        updated_at = ? WHERE id = ?""", model, Timestamp.from(now), Timestamp.from(now), r.jobId());
        }
    }

    /** 보관본 재처리 실패: 사진은 서버에 있으므로 RECEIVED 그대로 TECHNICAL_FAILED(시니어 재시도 가능). 품질 횟수 그대로. */
    private Finished reprocessFailed(Reservation r, String code) {
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE inspections SET capture_status = 'TECHNICAL_FAILED', ingestion_lease_token = NULL, ingestion_lease_until = NULL,
                    updated_at = ? WHERE id = ?""", Timestamp.from(now), r.inspectionId());
        jdbc.update("""
                UPDATE analysis_jobs SET status = 'FAILED', error_code = ?, lease_token = NULL, locked_until = NULL, finished_at = ?, updated_at = ?
                WHERE id = ?""", code, Timestamp.from(now), Timestamp.from(now), r.jobId());
        UUID slot = jdbc.queryForObject("SELECT capture_slot_id FROM inspections WHERE id = ?", UUID.class, r.inspectionId());
        jdbc.update("""
                UPDATE capture_slots SET active_inspection_id = NULL, version = version + 1, updated_at = ? WHERE id = ? AND active_inspection_id = ?""",
                Timestamp.from(now), slot, r.inspectionId());
        stops.settle(jdbc.queryForObject("SELECT patrol_stop_id FROM capture_slots WHERE id = ?", UUID.class, slot));
        CaptureResults.Context c = results.context(r.inspectionId()).orElseThrow();
        support.bumpRevision(c.patrolId());
        return new Finished(new Submitted(HttpStatus.ACCEPTED, results.of(r.inspectionId()).submitted(false)), null);
    }

    private Finished retryRequired(Reservation r, String code) {
        markRetryRequired(r.inspectionId(), code);
        support.bumpRevision(results.context(r.inspectionId()).orElseThrow().patrolId());
        return new Finished(null, analysisUnavailable(r.inspectionId()));
    }

    private static ApiException analysisUnavailable(UUID inspectionId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inspectionId", inspectionId);
        details.put("receiptStatus", "RETRY_REQUIRED");
        details.put("retryAfterSeconds", RETRY_AFTER_SECONDS);
        return new ApiException(ErrorCode.ANALYSIS_UNAVAILABLE, "지금은 사진을 받을 수 없어요. 사진은 휴대폰에 보관하고 잠시 후 다시 보낼게요.",
                details);
    }

    /**
     * 사진을 확보하지 못한 시도: RETRY_REQUIRED(같은 ID·파일 재전송), 품질 횟수 그대로. 구도 처리 중 포인터를 풀어 다른 조원 사진을 막지 않고,
     * D-39로 열었던 구도는 다시 SKIPPED로 돌린다. 호출 전 구도·사진을 잠가 둔다.
     */
    private void markRetryRequired(UUID inspectionId, String code) {
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE inspections SET receipt_status = 'RETRY_REQUIRED', capture_status = 'TECHNICAL_FAILED', ingestion_lease_token = NULL,
                    ingestion_lease_until = NULL, updated_at = ? WHERE id = ?""", Timestamp.from(now), inspectionId);
        jdbc.update("""
                UPDATE analysis_jobs SET status = 'FAILED', error_code = ?, lease_token = NULL, locked_until = NULL, finished_at = ?, updated_at = ?
                WHERE inspection_id = ? AND type = 'ANALYZE' AND status = 'PROCESSING'""", code, Timestamp.from(now), Timestamp.from(now),
                inspectionId);
        UUID slot = jdbc.queryForObject("SELECT capture_slot_id FROM inspections WHERE id = ?", UUID.class, inspectionId);
        jdbc.update("""
                UPDATE capture_slots SET active_inspection_id = NULL,
                    resolution = CASE WHEN skipped_by_event_id IS NOT NULL AND resolution = 'OPEN' THEN 'SKIPPED' ELSE resolution END,
                    version = version + 1, updated_at = ? WHERE id = ? AND active_inspection_id = ?""", Timestamp.from(now), slot, inspectionId);
        stops.settle(jdbc.queryForObject("SELECT patrol_stop_id FROM capture_slots WHERE id = ?", UUID.class, slot));
    }

    private static ApiException imageProcessingFailed(CaptureResult r) {
        boolean review = "REVIEW_REQUESTED".equals(r.captureStatus());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inspectionId", r.inspectionId());
        details.put("receiptStatus", r.receiptStatus());
        details.put("captureStatus", r.captureStatus());
        details.put("nextAction", r.nextAction());
        details.put("retakesRemaining", r.retakesRemaining());
        details.put("patrolRevision", r.patrolRevision());
        details.put("qualityFailureCount", r.qualityFailureCount());
        return new ApiException(ErrorCode.IMAGE_PROCESSING_FAILED,
                review ? "사진을 처리하지 못했어요. 다시 찍으러 가지 않으셔도 되고, 담당자가 확인할게요." : "사진을 처리하지 못했어요. 다시 찍어 주세요.",
                details);
    }

    private static ApiException submissionConflict(UUID existingId, UUID clientSubmissionId) {
        return new ApiException(ErrorCode.SUBMISSION_CONFLICT, "같은 제출 번호로 다른 사진이 전송되었습니다.",
                Map.of("existingInspectionId", existingId, "clientSubmissionId", clientSubmissionId));
    }

    private static ApiException slotResolved(SlotState slot) {
        return new ApiException(ErrorCode.SLOT_RESOLVED, "이미 처리가 끝난 사진 순서예요. 최신 상태를 불러올게요.",
                Map.of("captureSlotId", slot.id(), "resolution", slot.resolution()));
    }

    private static Instant ts(java.time.OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
