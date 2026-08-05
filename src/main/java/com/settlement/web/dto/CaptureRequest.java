package com.settlement.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code { "idempotency_key": "..." }} */
public record CaptureRequest(
        @NotBlank(message = "is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String idempotencyKey) {
}
