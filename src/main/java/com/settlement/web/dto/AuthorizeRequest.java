package com.settlement.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * {@code { "amount": 125000, "currency": "INR", "idempotency_key": "..." }}
 *
 * <p>{@code amount} is integer minor units (paise). It is a {@code Long}, never
 * a floating point type, and {@code @Positive} rejects zero and negatives
 * before any money logic runs.
 */
public record AuthorizeRequest(
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
