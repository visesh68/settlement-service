package com.settlement;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.settlement.support.IntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service settles on its own. Every other test drives draining explicitly
 * so it can assert on exact moments; this one leaves the tick enabled and pokes
 * nothing, which is the behaviour a deployed instance actually needs.
 */
@TestPropertySource(properties = {
        "app.drain.enabled=true",
        "app.drain.interval=200ms"
})
// This is the only context in the suite with a live background drainer, and
// Spring caches contexts for the whole run rather than closing them after the
// class. Without this, the tick keeps firing against the shared database while
// *later* test classes run, claiming and settling their outbox rows: dead-letter
// tests find nothing dead-lettered, and the crash test finds its work already
// drained. That failure is invisible locally, because it only bites when this
// class happens to run before the others, which depends on the filesystem
// ordering Surefire sees - so it passed on Windows and failed on Linux CI.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SelfDrivingDrainTest extends IntegrationTest {

    @Test
    @DisplayName("captures settle on the background tick without anyone calling /admin/drain")
    void backgroundTickSettlesEverything() {
        setFailureRate(0.4);
        List<UUID> paymentIds = inParallel(12, this::authorizeAndCapture);

        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(
                        stats().get("invariants").get("fully_drained").asBoolean()).isTrue());

        for (UUID paymentId : paymentIds) {
            assertThat(settlementStatus(paymentId)).isEqualTo("SETTLED");
            assertThat(settlementCountFor(paymentId)).isEqualTo(1);
        }
        assertThat(stats().get("invariants").get("safety_holds").asBoolean()).isTrue();
        assertThat(distinctSettlementKeys()).isEqualTo(paymentIds.size());
    }

    @Test
    @DisplayName("the tick and a manual drain running together never double-settle")
    void manualDrainRacingTheTickIsSafe() {
        setFailureRate(0.4);
        List<UUID> paymentIds = inParallel(12, this::authorizeAndCapture);

        // Hammer /admin/drain while the tick is also running. Both take the same
        // path, so this is two concurrent drainers inside one process.
        for (int i = 0; i < 15; i++) {
            inParallel(3, () -> drain(1));
        }

        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(
                        stats().get("invariants").get("fully_drained").asBoolean()).isTrue());

        assertThat(stats().get("invariants").get("double_settled_payments").asLong()).isZero();
        for (UUID paymentId : paymentIds) {
            assertThat(settlementCountFor(paymentId)).isEqualTo(1);
        }
    }
}
