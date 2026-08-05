package com.settlement.domain;

import java.time.Instant;
import java.util.UUID;

public record Payment(
        UUID id,
        String name,
        long amountMinor,
        String currency,
        PaymentState state,
        long version,
        String correlationId,
        Instant createdAt,
        Instant authorizedAt,
        Instant capturedAt,
        Instant failedAt) {
}
