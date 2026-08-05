package com.settlement.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * {@code { "name": "Acme invoice #42", "amount": 125000, "currency": "INR", "idempotency_key": "..." }}
 *
 * <p>{@code amount} is integer minor units (paise). It is a {@code Long}, never
 * a floating point type, and {@code @Positive} rejects zero and negatives
 * before any money logic runs.
 *
 * <p>{@code name} is an optional human-readable label. It is not an identifier:
 * it is not unique and nothing branches on it. It is, however, part of the
 * request identity for idempotency purposes — replaying a key with a different
 * name is a different request and answers 409, exactly as a different amount does.
 */
public record AuthorizeRequest(
        @Size(max = 120, message = "must be at most 120 characters")
        String name,

        @NotNull(message = "is required")
        @Positive(message = "must be a positive integer number of minor units")
        Long amount,

        @NotBlank(message = "is required")
        @Size(min = 3, max = 3, message = "must be a 3-letter ISO-4217 code")
        String currency,

        @NotBlank(message = "is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String idempotencyKey) {
}
