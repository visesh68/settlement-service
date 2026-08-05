package com.settlement.domain;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyRecord(
        String scope,
        String idempotencyKey,
        String requestFingerprint,
        UUID paymentId,
        int responseStatus,
        String responseBody,
        String correlationId,
        Instant createdAt) {
}
