package com.settlement;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.settlement.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Terminal failure must be loud. An item that cannot be settled after its cap
 * of retries is moved to a dead-letter state where it stays visible and
 * queryable — it is never dropped, and never quietly retried forever.
 */
@TestPropertySource(properties = {
        "app.settlement.max-attempts=3",
        "app.settlement.backoff-base=5ms",
        "app.settlement.backoff-max=10ms"
})
class DeadLetterTest extends IntegrationTest {

    @Test
    @DisplayName("a permanently failing downstream dead-letters after the attempt cap, and settles nothing")
    void permanentFailureDeadLetters() {
        setFailureRate(1.0);
        UUID paymentId = authorizeAndCapture();

        drainUntilQuiet(40);

        assertThat(settlementStatus(paymentId)).isEqualTo("DEAD_LETTER");
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM settlement_outbox WHERE payment_id = ?", Integer.class, paymentId))
                .as("the cap is a cap, not a suggestion")
                .isEqualTo(3);
        assertThat(settlementCountFor(paymentId))
                .as("a dead-lettered payment was never settled downstream")
                .isZero();

        // The payment itself stays CAPTURED: the money was captured, it is the
        // settlement that failed, and conflating the two would lose information.
        assertThat(paymentState(paymentId)).isEqualTo("CAPTURED");
    }

    @Test
    @DisplayName("dead letters are listed with the reason, never silently dropped")
    void deadLettersAreVisible() {
        setFailureRate(1.0);
        UUID paymentId = authorizeAndCapture();
        drainUntilQuiet(40);

        JsonNode deadLetters = body(get("/admin/dead-letters"));
        assertThat(deadLetters).hasSize(1);
        JsonNode item = deadLetters.get(0);
        assertThat(item.get("payment_id").asText()).isEqualTo(paymentId.toString());
        assertThat(item.get("status").asText()).isEqualTo("DEAD_LETTER");
        assertThat(item.get("last_error").asText()).contains("HTTP 500");

        JsonNode invariants = stats().get("invariants");
        assertThat(invariants.get("dead_lettered").asLong()).isEqualTo(1);
        assertThat(invariants.get("fully_drained").asBoolean())
                .as("dead-lettered is terminal, so the outbox is quiescent")
                .isTrue();
        assertThat(invariants.get("safety_holds").asBoolean())
                .as("failing to settle is not a safety violation; settling twice would be")
                .isTrue();
    }

    @Test
    @DisplayName("a dead-lettered item is not retried by later drains")
    void deadLetteredItemsAreNotReclaimed() {
        setFailureRate(1.0);
        UUID paymentId = authorizeAndCapture();
        drainUntilQuiet(40);
        assertThat(settlementStatus(paymentId)).isEqualTo("DEAD_LETTER");

        long deliveriesBefore = count("SELECT count(*) FROM mock_settlements");
        setFailureRate(0.0);
        drain(10);

        assertThat(settlementStatus(paymentId))
                .as("recovery from dead letter is a deliberate operator action, not an accident")
                .isEqualTo("DEAD_LETTER");
        assertThat(count("SELECT count(*) FROM mock_settlements")).isEqualTo(deliveriesBefore);
    }

    @Test
    @DisplayName("a downstream that recovers part-way through settles the item without dead-lettering it")
    void recoveringDownstreamStillSettles() {
        setFailureRate(1.0);
        UUID paymentId = authorizeAndCapture();

        drain(1); // one failed attempt
        assertThat(settlementStatus(paymentId)).isEqualTo("PENDING");

        setFailureRate(0.0);
        assertThat(drainUntilQuiet(30)).isTrue();

        assertThat(settlementStatus(paymentId)).isEqualTo("SETTLED");
        assertThat(settlementCountFor(paymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM settlement_outbox WHERE payment_id = ?", Integer.class, paymentId))
                .isGreaterThanOrEqualTo(2);
    }
}
