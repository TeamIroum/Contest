package kr.iroum.inspection.issue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import kr.iroum.inspection.analysis.client.AiClient;
import kr.iroum.inspection.analysis.client.AiClient.AiException;
import kr.iroum.inspection.auth.session.AuthenticatedUser;
import kr.iroum.inspection.common.audit.AuditLog;
import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse.FieldError;
import kr.iroum.inspection.common.idempotency.IdempotencyService;
import kr.iroum.inspection.inspection.DeviceMaskReport;
import kr.iroum.inspection.inspection.ImageInspector;
import kr.iroum.inspection.media.MediaStorage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 보수 증거 사진 업로드(업무 API 10절 POST /admin/media, purpose=REPAIR_EVIDENCE, MEDIA-UP-01).
 * <ul>
 * <li>시설 담당자(문제 시설의 현재 관할)만, 문제가 REPAIR_REQUESTED·IN_REPAIR일 때(보수 완료 전). 기기 마스킹 사진만 받는다.</li>
 * <li>AI /privacy/mask 검사 완료본만 저장한다. 원본은 메모리에서만 다루고 저장소에 쓰지 않는다. AI를 부르는 동안 DB 잠금을 잡지 않는다.</li>
 * <li>AI 연결 불가·일시 장애·인증 거절은 503 PRIVACY_SERVICE_NOT_READY(저장 없음), 마스킹 실패(status=error)·사진 거절은 422 IMAGE_PROCESSING_FAILED.</li>
 * <li>저장 뒤 DB 반영(시설 잠금·관할 재확인·문제 상태 재확인·media 행·감사·멱등)이 실패하거나 같은 키가 먼저 끝났으면 저장한 사본을 지운다.</li>
 * </ul>
 * 참고 사진(REFERENCE)은 업로드 주체·연결 대상(구도 참고 사진 편집)이 정해지지 않아 받지 않는다(06단계 E10).
 */
@Service
public class RepairEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(RepairEvidenceService.class);
    private static final String OPERATION = "POST /admin/media";

    public record EvidenceResult(UUID mediaId, String purpose, UUID issueId, String privacyStatus, int mediaVersion, String contentPath) {
    }

    public record Upload(String purpose, UUID issueId, UUID organizationId, String deviceMasked, String deviceMaskReport, String maskingVersion) {
    }

    private final JdbcTemplate jdbc;
    private final IssueLocks locks;
    private final ImageInspector images;
    private final AiClient ai;
    private final MediaStorage storage;
    private final IdempotencyService idempotency;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final long retryAfterSeconds;

    public RepairEvidenceService(JdbcTemplate jdbc, IssueLocks locks, ImageInspector images, AiClient ai, MediaStorage storage,
            IdempotencyService idempotency, AuditLog audit, TransactionTemplate tx, JsonMapper json, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${inspection.privacy-mask.retry-after:30s}") java.time.Duration retryAfter) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.images = images;
        this.ai = ai;
        this.storage = storage;
        this.idempotency = idempotency;
        this.audit = audit;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.retryAfterSeconds = Math.max(1, retryAfter.toSeconds());
    }

    public IdempotencyService.Outcome<EvidenceResult> upload(AuthenticatedUser user, UUID key, Upload in, byte[] file) {
        List<FieldError> e = new ArrayList<>();
        if (!"REPAIR_EVIDENCE".equals(in.purpose())) {
            e.add(new FieldError("purpose", in.purpose() == null ? "REQUIRED" : "INVALID_FORMAT",
                    "지금은 보수 증거 사진(REPAIR_EVIDENCE)만 올릴 수 있습니다."));
        }
        if (in.issueId() == null) {
            e.add(new FieldError("issueId", "REQUIRED", "보수 증거 사진은 문제 ID가 필요합니다."));
        }
        if (in.organizationId() != null) {
            e.add(new FieldError("organizationId", "INVALID_FORMAT", "보수 증거 사진에는 기관 ID를 보내지 않습니다."));
        }
        if (!"true".equals(in.deviceMasked())) {
            e.add(new FieldError("deviceMasked", in.deviceMasked() == null ? "REQUIRED" : "INVALID_FORMAT", "기기에서 가린 사진만 받습니다."));
        }
        if (in.maskingVersion() == null || in.maskingVersion().isBlank()) {
            e.add(new FieldError("maskingVersion", "REQUIRED", "마스킹 버전이 필요합니다."));
        } else if (in.maskingVersion().length() > 50) {
            e.add(new FieldError("maskingVersion", "TOO_LONG", "50자 이하여야 합니다."));
        }
        if (file == null || file.length == 0) {
            e.add(new FieldError("file", "REQUIRED", "사진 파일이 필요합니다."));
        }
        if (!e.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, e);
        }
        locks.requireScope(user, in.issueId());
        DeviceMaskReport report = DeviceMaskReport.parse(in.deviceMaskReport(), json);
        ImageInspector.Checked image = images.inspect(file);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("purpose", in.purpose());
        identity.put("issueId", in.issueId());
        identity.put("fileSha256", HexFormat.of().formatHex(sha256(file)));
        identity.put("deviceMaskReport", report.toAi(true));
        identity.put("maskingVersion", in.maskingVersion());
        Optional<IdempotencyService.Outcome<EvidenceResult>> replay = idempotency.find(user.userId(), OPERATION, key, identity, EvidenceResult.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireOpenRepair(in.issueId());
        // 1) 잠금 없이 AI 검사와 검사 완료본 저장.
        UUID mediaId = UUID.randomUUID();
        String photoId = "m_" + mediaId;
        AiClient.MaskedImage masked;
        JsonNode result;
        try {
            result = ai.privacyMask(mediaId.toString(), image.bytes(), image.contentType(), "repair_evidence", report.toAi(!report.boxes().isEmpty()));
            if (!"ok".equals(result.path("status").asString(""))) {
                throw processingFailed();
            }
            masked = ai.maskedImage(photoId);
        } catch (AiException ex) {
            if (ex.transientFailure() || ex.status() == 401 || ex.status() == 403) {
                if (!ex.transientFailure()) {
                    log.error("AI 개인정보 검사 인증·권한 거절(HTTP {}): API 키·설정 확인 필요", ex.status());
                }
                throw new ApiException(ErrorCode.PRIVACY_SERVICE_NOT_READY, "사진 개인정보 검사를 지금 할 수 없습니다. 잠시 후 다시 올려 주세요.",
                        Map.of("retryAfterSeconds", retryAfterSeconds));
            }
            throw processingFailed();
        }
        Object issue = jdbc.queryForObject("SELECT program_id FROM issues WHERE id = ?", UUID.class, in.issueId());
        String objectKey = issue + "/repair/" + in.issueId() + "/" + mediaId + "/r" + masked.revision() + "-" + UUID.randomUUID()
                + ("image/png".equals(masked.contentType()) ? ".png" : ".jpg");
        try {
            storage.put(MediaStorage.BUCKET, objectKey, masked.bytes());
        } catch (java.io.UncheckedIOException ex) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "사진을 저장하지 못했습니다. 잠시 후 다시 올려 주세요.",
                    Map.of("retryAfterSeconds", retryAfterSeconds));
        }
        // 2) 짧은 트랜잭션: 관할·상태 재확인 후 media 행·감사·멱등 결과.
        try {
            IdempotencyService.Outcome<EvidenceResult> out = tx.execute(s -> idempotency.execute(user.userId(), OPERATION, key, identity,
                    EvidenceResult.class, 201, () -> {
                        Map<String, Object> row = locks.lock(user, in.issueId());
                        IssueLocks.requireStatus(row, "REPAIR_REQUESTED", "IN_REPAIR");
                        UUID program = (UUID) row.get("program_id");
                        Instant now = clock.instant();
                        jdbc.update("""
                                INSERT INTO media (id, program_id, uploaded_by, bucket, object_key, purpose, privacy_status, content_type, size_bytes, sha256,
                                    provider_photo_id, provider_image_revision, privacy_report_json, issue_id, created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?, 'REPAIR_EVIDENCE', 'APPROVED', ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""",
                                mediaId, program, user.userId(), MediaStorage.BUCKET, objectKey, masked.contentType(), (long) masked.bytes().length,
                                sha256(masked.bytes()), photoId, masked.revision(), json.writeValueAsString(privacySummary(result)), in.issueId(),
                                Timestamp.from(now), Timestamp.from(now));
                        audit.record(program, user.userId(), "REPAIR_EVIDENCE_UPLOADED", "ISSUE", in.issueId(), null, Map.of(),
                                Map.of("mediaId", mediaId, "maskingVersion", in.maskingVersion()));
                        return new EvidenceResult(mediaId, "REPAIR_EVIDENCE", in.issueId(), "APPROVED", masked.revision(),
                                "/api/v1/media/" + mediaId + "/content");
                    }));
            if (out.replayed()) {
                storage.deleteQuietly(MediaStorage.BUCKET, objectKey);
            }
            return out;
        } catch (RuntimeException ex) {
            storage.deleteQuietly(MediaStorage.BUCKET, objectKey);
            throw ex;
        }
    }

    /** AI 호출 전 상태 확인(보수 완료 전). 잠금 뒤 다시 확인한다. */
    private void requireOpenRepair(UUID issueId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT id, status FROM issues WHERE id = ?", issueId);
        IssueLocks.requireStatus(row, "REPAIR_REQUESTED", "IN_REPAIR");
    }

    /** 개인정보 검사 요약만 남긴다(사진 주소·원문 판독 내용 없음). */
    private static Map<String, Object> privacySummary(JsonNode result) {
        JsonNode p = result.path("privacy");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("faces", p.path("faces").asInt(0));
        out.put("plates", p.path("plates").asInt(0));
        out.put("vehicles", p.path("vehicles").asInt(0));
        out.put("serverAdded", p.path("server_added").asInt(0));
        out.put("mode", p.path("mode").asString(""));
        return out;
    }

    private static ApiException processingFailed() {
        return new ApiException(ErrorCode.IMAGE_PROCESSING_FAILED, "사진에서 개인정보 가림을 확인하지 못했습니다. 다시 찍어 올려 주세요.");
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
