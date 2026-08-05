package com.settlement;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.settlement.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness gate 2: exactly-once settlement against the flaky downstream.
 *
 * <p>The downstream fails ~40% of calls, so a batch of thirty payments will take
 * hundreds of delivery attempts to finish. The assertions that matter are not
 * "it eventually finished" but:
 *
 * <ul>
 *   <li>the provider settled each captured payment exactly once — asserted
 *       against the <em>provider's own ledger</em>, not our bookkeeping;</li>
 *   <li>far more deliveries happened than settlements, which is the evidence
 *       that retries genuinely occurred and were genuinely deduplicated rather
 *       than the test having got lucky and never retried anything;</li>
 *   <li>nothing was lost: every captured payment is terminal, and any terminal
 *       failure is visible as a dead letter.</li>
 * </ul>
 */
class ExactlyOnceSettlementTest extends IntegrationTest {

    private static final int BATCH = 30;

    @Test
    @DisplayName("a batch settles exactly once each despite a 40% failure rate")
    void batchSettlesExactlyOnceUnderFlakyDownstream() {
        setFailureRate(0.4);

        List<UUID> paymentIds = inParallel(BATCH, this::authorizeAndCapture);
        assertThat(capturedCount()).isEqualTo(BATCH);

        boolean quiet = drainUntilQuiet(60);
        assertThat(quiet).as("the outbox should drain to quiescence").isTrue();

        JsonNode invariants = stats().get("invariants");
        assertThat(invariants.get("safety_holds").asBoolean()).isTrue();
        assertThat(invariants.get("double_settled_payments").asLong()).isZero();
        assertThat(invariants.get("captured_without_outbox_row").asLong()).isZero();
        assertThat(invariants.get("settled_but_not_captured").asLong()).isZero();

        // Settlement-key count == captured count, which is the gate's own phrasing.
        assertThat(distinctSettlementKeys())
                .as("one settlement key per captured payment, no more and no fewer")
                .isEqualTo(BATCH);

        for (UUID paymentId : paymentIds) {
            assertThat(settlementCountFor(paymentId))
                    .as("payment %s must be settled exactly once", paymentId)
                    .isEqualTo(1);
            assertThat(settlementStatus(paymentId)).isEqualTo("SETTLED");
        }

        // Proof the retry path was genuinely exercised rather than the test
        // having got lucky and settled everything first time: with p(fail) = 0.4
        // over 30 items, the chance of no item ever needing a second attempt is
        // about 2e-7. Attempts are counted on the outbox row; the provider's own
        // delivery counter only sees redeliveries of keys it already settled,
        // which is a different (and rarer) thing.
        assertThat(count("SELECT COALESCE(sum(attempts), 0) FROM settlement_outbox"))
                .as("settling 30 items against a 40%%-failure downstream must take more than 30 attempts")
                .isGreaterThan(BATCH);
    }

    @Test
    @DisplayName("four drainers running concurrently never double-settle")
    void concurrentDrainersDoNotDoubleSettle() {
        setFailureRate(0.4);

        List<UUID> paymentIds = inParallel(BATCH, this::authorizeAndCapture);

        // Four simultaneous drain passes, repeated. FOR UPDATE SKIP LOCKED must
        // give each pass a disjoint set of rows; if it did not, the same
        // settlement key would be delivered by two drainers at once and the
        // provider's ledger would be the place it showed up.
        boolean drained = false;
        for (int round = 0; round < 40 && !drained; round++) {
            inParallel(4, () -> drain(2));
            drained = stats().get("invariants").get("fully_drained").asBoolean();
        }

        JsonNode invariants = stats().get("invariants");
        assertThat(drained)
                .as("the batch must reach quiescence before 'settled exactly once' means anything")
                .isTrue();
        assertThat(invariants.get("double_settled_payments").asLong())
                .as("no payment may be settled twice, however many drainers run")
                .isZero();
        assertThat(invariants.get("safety_holds").asBoolean()).isTrue();
        assertThat(distinctSettlementKeys()).isEqualTo(BATCH);
        for (UUID paymentId : paymentIds) {
            assertThat(settlementCountFor(paymentId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("draining is idempotent: settled items are never re-delivered")
    void settledItemsAreNeverRedelivered() {
        setFailureRate(0.0);
        UUID paymentId = authorizeAndCapture();

        assertThat(drainUntilQuiet(20)).isTrue();
        assertThat(settlementStatus(paymentId)).isEqualTo("SETTLED");
        long deliveriesAfterSettling = totalDeliveries();

        // Ten more drain passes over an already-settled outbox.
        drain(10);

        assertThat(totalDeliveries())
                .as("a settled item is not claimable, so nothing is re-delivered")
                .isEqualTo(deliveriesAfterSettling);
        assertThat(settlementCountFor(paymentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("capture enqueues settlement transactionally: no capture without a job, no job without a capture")
    void outboxIsWrittenInTheCaptureTransaction() {
        setFailureRate(0.0);
        UUID captured = authorizeAndCapture();
        UUID authorizedOnly = authorize();

        assertThat(outboxCount(captured)).isEqualTo(1);
        assertThat(outboxCount(authorizedOnly)).isZero();
        assertThat(count("""
                SELECT count(*) FROM payments p
                 WHERE p.state = 'CAPTURED'
                   AND NOT EXISTS (SELECT 1 FROM settlement_outbox o WHERE o.payment_id = p.id)
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM settlement_outbox o
                  JOIN payments p ON p.id = o.payment_id
                 WHERE p.state <> 'CAPTURED'
                """)).isZero();
    }

    @Test
    @DisplayName("attempts and timings are reported per payment")
    void paymentExposesSettlementProgress() {
        setFailureRate(0.0);
        UUID paymentId = authorizeAndCapture();
        drainUntilQuiet(20);

        JsonNode payment = body(get("/payments/" + paymentId));
        assertThat(payment.get("state").asText()).isEqualTo("CAPTURED");
        assertThat(payment.get("settlement_state").asText()).isEqualTo("SETTLED");
        assertThat(payment.get("attempts").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode timings = payment.get("timings");
        assertThat(timings.get("authorized_at").isNull()).isFalse();
        assertThat(timings.get("captured_at").isNull()).isFalse();
        assertThat(timings.get("first_settlement_attempt_at").isNull()).isFalse();
        assertThat(timings.get("settled_at").isNull()).isFalse();
    }
}
