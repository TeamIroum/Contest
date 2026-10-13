package kr.iroum.inspection.common.error;

import java.util.List;
import java.util.Map;

/** 업무 규칙 위반을 계약된 오류 코드로 응답할 때 사용한다. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;
    private final transient List<ErrorResponse.FieldError> fieldErrors;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, List.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details, List<ErrorResponse.FieldError> fieldErrors) {
        super(message);
        this.code = code;
        this.details = details;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public List<ErrorResponse.FieldError> fieldErrors() {
        return fieldErrors;
    }
}
