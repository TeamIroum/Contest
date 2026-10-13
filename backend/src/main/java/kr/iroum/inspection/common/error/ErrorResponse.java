package kr.iroum.inspection.common.error;

import java.util.List;
import java.util.Map;

/**
 * 공통 오류 본문. 다섯 키는 항상 보내며 details가 없으면 null이다(업무 API 2.1절).
 */
public record ErrorResponse(
        String code,
        String message,
        String requestId,
        List<FieldError> fieldErrors,
        Map<String, Object> details) {

    public record FieldError(String field, String reason, String message) {
    }
}
