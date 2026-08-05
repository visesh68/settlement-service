package com.settlement.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    /** Amount <= 0, unknown currency, missing/oversized idempotency key, malformed id. */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),

    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND),

    /**
     * The idempotency key was already used for a *different* request body.
     * Required by the spec: same key + different body -> 409.
     */
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),

    /** Capture attempted on a payment that is already CAPTURED. */
    ALREADY_CAPTURED(HttpStatus.CONFLICT),

    /** Capture or void attempted from a state that does not allow it. */
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
