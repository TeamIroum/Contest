package kr.iroum.inspection.common.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        ErrorResponse body = ErrorResponses.of(e.code(), e.getMessage(), e.fieldErrors(), e.details());
        // 429는 details.retryAfterSeconds와 같은 값을 Retry-After로도 준다(업무 API 2.2절).
        if (e.code() == ErrorCode.RATE_LIMITED && e.details() != null && e.details().get("retryAfterSeconds") != null) {
            return ResponseEntity.status(e.code().status())
                    .header("Retry-After", String.valueOf(e.details().get("retryAfterSeconds"))).body(body);
        }
        return respond(e.code(), body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorResponse.FieldError(f.getField(), reason(f.getCode()), f.getDefaultMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED,
                ErrorResponses.of(ErrorCode.VALIDATION_FAILED, "입력 내용을 확인해 주세요.", fields, null));
    }

    /** 필수 헤더·쿼리 누락, 경로·쿼리 형식 오류(UUID·날짜·enum)는 400이다. */
    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    ResponseEntity<ErrorResponse> handleBadParameter(Exception e) {
        return respond(ErrorCode.VALIDATION_FAILED, ErrorResponses.of(ErrorCode.VALIDATION_FAILED, "요청 형식을 확인해 주세요."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return respond(ErrorCode.VALIDATION_FAILED, ErrorResponses.of(ErrorCode.VALIDATION_FAILED, "요청 형식을 확인해 주세요."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        return respond(ErrorCode.PAYLOAD_TOO_LARGE, ErrorResponses.of(ErrorCode.PAYLOAD_TOO_LARGE, "사진 크기가 너무 커요."));
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ErrorResponse> handleNotFound(Exception e) {
        return respond(ErrorCode.NOT_FOUND, ErrorResponses.of(ErrorCode.NOT_FOUND, "찾을 수 없거나 접근할 수 없는 항목입니다."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 원인은 서버 로그에만 남기고 응답에는 내부 정보를 넣지 않는다.
        log.error("처리되지 않은 오류", e);
        return respond(ErrorCode.INTERNAL_ERROR,
                ErrorResponses.of(ErrorCode.INTERNAL_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code, ErrorResponse body) {
        return ResponseEntity.status(code.status()).body(body);
    }

    private static String reason(String constraint) {
        if (constraint == null) {
            return "INVALID_FORMAT";
        }
        return switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            case "Size", "Length" -> "TOO_LONG";
            case "Min", "Max", "Positive", "PositiveOrZero", "DecimalMin", "DecimalMax" -> "OUT_OF_RANGE";
            default -> "INVALID_FORMAT";
        };
    }
}
