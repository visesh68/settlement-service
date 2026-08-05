package com.settlement.error;

import java.util.Map;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.PAYMENT_NOT_FOUND, message);
    }

    public static ApiException keyReused(String scope) {
        return new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "This idempotency key was already used for a different " + scope + " request");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // These are expected control-flow outcomes (409s are a normal, frequent
        // result under concurrency); capturing stack traces for them is waste.
        return this;
    }
}
