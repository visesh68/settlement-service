package com.settlement.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.settlement.domain.OutboxItem;
import com.settlement.domain.Payment;

/**
 * The canonical payment representation. GET /payments/{id} returns it, and it
 * is also what gets stored verbatim in the idempotency record so a replayed
 * request returns the same bytes it returned the first time.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentView(
        UUID id,
        long amount,
        String currency,
        String state,
        String settlementState,
        int attempts,
        UUID settlementKey,
        String lastError,
        Timings timings) {

    /** Settlement state for a payment that has not been captured yet. */
    public static final String NOT_APPLICABLE = "NONE";

    public static PaymentView of(Payment payment, OutboxItem outbox) {
        return new PaymentView(
                payment.id(),
                payment.amountMinor(),
                payment.currency(),
                payment.state().name(),
                outbox == null ? NOT_APPLICABLE : outbox.status().name(),
                outbox == null ? 0 : outbox.attempts(),
                outbox == null ? null : outbox.settlementKey(),
                outbox == null ? null : outbox.lastError(),
                new Timings(
                        payment.authorizedAt(),
                        payment.capturedAt(),
                        outbox == null ? null : outbox.firstAttemptAt(),
                        outbox == null ? null : outbox.nextAttemptAt(),
                        outbox == null ? null : outbox.settledAt()));
    }

    public record Timings(
            Instant authorizedAt,
            Instant capturedAt,
            Instant firstSettlementAttemptAt,
            Instant nextSettlementAttemptAt,
            Instant settledAt) {
    }
}
