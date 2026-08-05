package com.settlement.error;

import java.util.LinkedHashMap;
import java.util.Map;

import com.settlement.web.CorrelationIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every failure leaves through here with a stable JSON shape and a correlation
 * id, and — importantly for the correctness gate — an expected conflict is a
 * 409, never a 500. Only genuinely unexpected throwables produce a 5xx.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        if (ex.code().status().is5xxServerError()) {
            log.error("event=request_failed code={} msg={}", ex.code(), ex.getMessage(), ex);
        } else {
            log.info("event=request_rejected code={} status={} msg={}",
                    ex.code(), ex.code().status().value(), ex.getMessage());
        }
        return build(ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> handleValidation(Exception ex) {
        String message = switch (ex) {
            case MethodArgumentNotValidException e -> e.getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + " " + f.getDefaultMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("request validation failed");
            case MethodArgumentTypeMismatchException e -> e.getName() + " is not a valid " +
                    (e.getRequiredType() == null ? "value" : e.getRequiredType().getSimpleName());
            case HttpMessageNotReadableException ignored -> "request body is missing or malformed JSON";
            default -> ex.getMessage();
        };
        log.info("event=request_rejected code={} status=400 msg={}", ErrorCode.INVALID_REQUEST, message);
        return build(ErrorCode.INVALID_REQUEST, message, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("event=request_failed code={} msg={}", ErrorCode.INTERNAL_ERROR, ex.toString(), ex);
        // Deliberately opaque: internal messages can leak infrastructure detail.
        return build(ErrorCode.INTERNAL_ERROR, "internal error", Map.of());
    }

    private ResponseEntity<Map<String, Object>> build(ErrorCode code, String message, Map<String, Object> details) {
        HttpStatus status = code.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code.name().toLowerCase());
        body.put("message", message);
        if (!details.isEmpty()) {
            body.put("details", details);
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            body.put("correlation_id", correlationId);
        }
        return ResponseEntity.status(status).body(body);
    }
}
