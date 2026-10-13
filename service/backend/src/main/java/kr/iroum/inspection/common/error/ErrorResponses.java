package kr.iroum.inspection.common.error;

import java.util.List;
import java.util.Map;

import kr.iroum.inspection.common.web.RequestIdFilter;

/** 오류 본문 생성. 사용자 메시지에 내부 경로·토큰·사진 URL을 넣지 않는다. */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return of(code, message, List.of(), null);
    }

    public static ErrorResponse of(ErrorCode code, String message, List<ErrorResponse.FieldError> fieldErrors,
            Map<String, Object> details) {
        return new ErrorResponse(code.name(), message, RequestIdFilter.currentRequestId(), fieldErrors, details);
    }
}
