package kr.iroum.inspection.inspection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import kr.iroum.inspection.common.error.ApiException;
import kr.iroum.inspection.common.error.ErrorCode;
import kr.iroum.inspection.common.error.ErrorResponse.FieldError;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사진 제출 multipart의 글자 필드(업무 API 6.3절, OpenAPI PhotoUpload). 파일 검사는 {@link ImageInspector}가 한다.
 * 형식 오류는 모은 뒤 400 VALIDATION_FAILED fieldErrors로 한 번에 돌려준다. assignmentRef는 선택이며 멱등 해시에서 뺀다(설계안 4.8).
 */
public record PhotoUpload(UUID clientSubmissionId, UUID captureSlotId, UUID attemptId, Instant capturedAt, String maskingVersion,
        DeviceMaskReport report, BigDecimal latitude, BigDecimal longitude, BigDecimal accuracy, UUID assignmentRef) {

    public record Raw(String clientSubmissionId, String captureSlotId, String attemptId, String capturedAt, String maskingVersion,
            String deviceMasked, String deviceMaskReport, String latitude, String longitude, String locationAccuracyMeters,
            String assignmentRef) {
    }

    public static PhotoUpload parse(Raw raw, JsonMapper json) {
        List<FieldError> errors = new ArrayList<>();
        UUID submission = uuid("clientSubmissionId", raw.clientSubmissionId(), true, errors);
        UUID slot = uuid("captureSlotId", raw.captureSlotId(), true, errors);
        UUID attempt = uuid("attemptId", raw.attemptId(), true, errors);
        UUID ref = uuid("assignmentRef", raw.assignmentRef(), false, errors);
        Instant captured = null;
        if (blank(raw.capturedAt())) {
            errors.add(new FieldError("capturedAt", "REQUIRED", "촬영 시각이 필요합니다."));
        } else {
            try {
                captured = java.time.OffsetDateTime.parse(raw.capturedAt()).toInstant();
            } catch (DateTimeParseException e) {
                errors.add(new FieldError("capturedAt", "INVALID_FORMAT", "ISO 8601 시각이어야 합니다."));
            }
        }
        String masking = raw.maskingVersion();
        if (blank(masking)) {
            errors.add(new FieldError("maskingVersion", "REQUIRED", "마스킹 버전이 필요합니다."));
        } else if (masking.length() > 50) {
            errors.add(new FieldError("maskingVersion", "TOO_LONG", "50자 이하여야 합니다."));
        }
        if (!"true".equals(raw.deviceMasked())) {
            // 기기 마스킹 완료본만 받는다(업무 API 6.3절). 원본 업로드 경로를 두지 않는다.
            errors.add(new FieldError("deviceMasked", blank(raw.deviceMasked()) ? "REQUIRED" : "INVALID_FORMAT", "기기 마스킹 완료본만 보낼 수 있습니다."));
        }
        DeviceMaskReport report = null;
        try {
            report = DeviceMaskReport.parse(raw.deviceMaskReport(), json);
        } catch (ApiException e) {
            errors.addAll(e.fieldErrors());
        }
        BigDecimal lat = number("latitude", raw.latitude(), new BigDecimal("-90"), new BigDecimal("90"), errors);
        BigDecimal lon = number("longitude", raw.longitude(), new BigDecimal("-180"), new BigDecimal("180"), errors);
        BigDecimal acc = number("locationAccuracyMeters", raw.locationAccuracyMeters(), BigDecimal.ZERO, new BigDecimal("999999"), errors);
        if ((lat == null) != (lon == null)) {
            errors.add(new FieldError(lat == null ? "latitude" : "longitude", "REQUIRED", "위도와 경도는 함께 보내야 합니다."));
        }
        if (acc != null && lat == null) {
            errors.add(new FieldError("locationAccuracyMeters", "INVALID_FORMAT", "위치 없이 정확도만 보낼 수 없습니다."));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", null, errors);
        }
        return new PhotoUpload(submission, slot, attempt, captured, masking, report, lat, lon, acc, ref);
    }

    private static UUID uuid(String field, String value, boolean required, List<FieldError> errors) {
        if (blank(value)) {
            if (required) {
                errors.add(new FieldError(field, "REQUIRED", "필수 값입니다."));
            }
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError(field, "INVALID_FORMAT", "UUID 형식이어야 합니다."));
            return null;
        }
    }

    private static BigDecimal number(String field, String value, BigDecimal min, BigDecimal max, List<FieldError> errors) {
        if (blank(value)) {
            return null;
        }
        try {
            BigDecimal n = new BigDecimal(value.trim());
            if (n.compareTo(min) < 0 || n.compareTo(max) > 0) {
                errors.add(new FieldError(field, "OUT_OF_RANGE", min + "~" + max + " 범위여야 합니다."));
                return null;
            }
            return n;
        } catch (NumberFormatException e) {
            errors.add(new FieldError(field, "INVALID_FORMAT", "숫자여야 합니다."));
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
