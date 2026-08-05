package com.settlement.domain;

import java.time.Instant;
import java.util.UUID;

public record OutboxItem(
        long id,
        UUID paymentId,
        UUID settlementKey,
        long amountMinor,
        String currency,
        SettlementStatus status,
        int attempts,
        int maxAttempts,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        String lastError,
        String correlationId,
        Instant createdAt,
        Instant firstAttemptAt,
        Instant settledAt) {

    /** True when this attempt was the last one the item is allowed. */
    public boolean attemptsExhausted() {
        return attempts >= maxAttempts;
    }
}
