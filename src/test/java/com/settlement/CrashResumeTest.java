package com.settlement;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.settlement.domain.OutboxItem;
import com.settlement.repo.OutboxRepository;
import com.settlement.support.IntegrationTest;
import com.settlement.support.TestDatabase;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crash safety and resumability.
 *
 * <p>A drainer that dies mid-flight leaves exactly one trace: an outbox row in
 * IN_FLIGHT with a lease naming an owner that is never coming back. There is no
 * in-memory state to rebuild, so "resume after a crash" is entirely a question
 * of whether that lease is noticed and the work redelivered — under the same
 * settlement key, so redelivery cannot become a second settlement.
 *
 * <p>The nastiest case has its own test below: the process dying in the window
 * <em>after</em> the provider committed the settlement but <em>before</em> we
 * recorded it. That is the moment where a naive retry double-pays.
 */
class CrashResumeTest extends IntegrationTest {

    @Autowired
    private OutboxRepository outbox;

    @Test
    @DisplayName("a real second instance killed mid-drain has its work recovered and settled exactly once")
    void workStrandedByAKilledInstanceIsRecovered() {
        setFailureRate(0.0);
        List<UUID> paymentIds = inParallel(5, this::authorizeAndCapture);

        // A genuinely separate application instance: its own connection pool,
        // its own instance id, its own web server. It is configured so that its
        // settlement calls take five seconds, guaranteeing they are still in
        // flight when we kill it.
        ConfigurableApplicationContext doomed = startSecondInstance(Map.of(
                "app.instance-id", "doomed-instance",
                "app.settlement.lease-duration", "2s",
                "app.mock-downstream.min-latency", "5s",
                "app.mock-downstream.max-latency", "5s",
                "server.shutdown", "immediate"));

        try {
            SettlementDrainerHandle drainer = new SettlementDrainerHandle(doomed);
            CompletableFuture.runAsync(drainer::drain);

            // Wait until the doomed instance has actually taken the work.
            Awaitility.await().atMost(Duration.ofSeconds(20))
                    .until(() -> count("SELECT count(*) FROM settlement_outbox WHERE status = 'IN_FLIGHT'") > 0);
            assertThat(count("SELECT count(*) FROM settlement_outbox WHERE lease_owner = 'doomed-instance'"))
                    .isEqualTo(paymentIds.size());

            // Kill it. Abruptly, mid-HTTP-call.
            //
            // Closing the connection pool *before* the context is what makes
            // this a kill -9 rather than a shutdown: a process that dies gets no
            // chance to write "retry this later" for the work it was holding, so
            // the rows must be left IN_FLIGHT for the reaper to find. Closing the
            // context alone would let the dying instance tidy up after itself and
            // quietly test a much easier scenario.
            doomed.getBean(com.zaxxer.hikari.HikariDataSource.class).close();
            doomed.close();
        } finally {
            if (doomed.isActive()) {
                doomed.close();
            }
        }

        // Nothing was settled by the instance that died.
        assertThat(distinctSettlementKeys()).isZero();
        assertThat(count("SELECT count(*) FROM settlement_outbox WHERE status = 'IN_FLIGHT'"))
                .as("the work is stranded, exactly as a kill -9 would leave it")
                .isEqualTo(paymentIds.size());

        // The surviving instance recovers it once the lease expires.
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    drain(2);
                    assertThat(stats().get("invariants").get("fully_drained").asBoolean()).isTrue();
                });

        for (UUID paymentId : paymentIds) {
            assertThat(settlementStatus(paymentId)).isEqualTo("SETTLED");
            assertThat(settlementCountFor(paymentId))
                    .as("recovered work must settle exactly once, not twice")
                    .isEqualTo(1);
        }
        assertThat(stats().get("invariants").get("safety_holds").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a crash AFTER the provider settled but BEFORE we recorded it does not settle twice")
    void crashInTheDangerousWindowDoesNotDoublePay() {
        setFailureRate(0.0);
        UUID paymentId = authorizeAndCapture();
        UUID settlementKey = settlementKeyOf(paymentId);

        // Reproduce the exact window: the provider has committed the settlement,
        // and our process died before it could write SETTLED. The row is left
        // IN_FLIGHT under an owner that no longer exists, with an expired lease.
        jdbc.update("""
                INSERT INTO mock_settlements (settlement_key, payment_id, amount_minor, currency, provider_ref)
                VALUES (?, ?, 125000, 'INR', 'MSTL-PRECRASH')
                """, settlementKey, paymentId);
        strandInFlight("crashed-instance");

        assertThat(settlementStatus(paymentId)).isEqualTo("IN_FLIGHT");
        assertThat(distinctSettlementKeys()).isEqualTo(1);

        assertThat(drainUntilQuiet(30)).isTrue();

        assertThat(settlementStatus(paymentId)).isEqualTo("SETTLED");
        assertThat(settlementCountFor(paymentId))
                .as("redelivering the same settlement key must not create a second settlement")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT provider_ref FROM mock_settlements WHERE settlement_key = ?", String.class, settlementKey))
                .as("the original settlement stands; the retry was deduplicated, not re-executed")
                .isEqualTo("MSTL-PRECRASH");
        assertThat(jdbc.queryForObject(
                "SELECT delivery_count FROM mock_settlements WHERE settlement_key = ?", Integer.class, settlementKey))
                .as("delivery happened twice; settlement happened once")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("stranded work is reclaimed, not lost, and attempts are preserved across the crash")
    void reclaimPreservesAttemptCount() {
        setFailureRate(0.0);
        UUID paymentId = authorizeAndCapture();

        strandInFlight("crashed-instance");
        int attemptsBeforeRecovery = jdbc.queryForObject(
                "SELECT attempts FROM settlement_outbox WHERE payment_id = ?", Integer.class, paymentId);
        assertThat(attemptsBeforeRecovery)
                .as("the attempt was counted at claim time, so a crash cannot make it free")
                .isEqualTo(1);

        List<OutboxItem> reclaimed = outbox.reclaimExpiredLeases();

        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.getFirst().status()).isEqualTo(com.settlement.domain.SettlementStatus.PENDING);
        assertThat(reclaimed.getFirst().attempts()).isEqualTo(attemptsBeforeRecovery);
        assertThat(jdbc.queryForObject(
                "SELECT last_error FROM settlement_outbox WHERE payment_id = ?", String.class, paymentId))
                .contains("lease expired");
    }

    @Test
    @DisplayName("stranded work with no attempts left dead-letters instead of cycling forever")
    void strandedWorkWithNoAttemptsLeftIsDeadLettered() {
        setFailureRate(0.0);
        UUID paymentId = authorizeAndCapture();

        strandInFlight("crashed-instance");
        // Burn every remaining attempt, as a genuinely poisonous item would.
        jdbc.update("UPDATE settlement_outbox SET attempts = max_attempts WHERE payment_id = ?", paymentId);

        List<OutboxItem> reclaimed = outbox.reclaimExpiredLeases();

        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.getFirst().status()).isEqualTo(com.settlement.domain.SettlementStatus.DEAD_LETTER);
        assertThat(settlementStatus(paymentId)).isEqualTo("DEAD_LETTER");

        JsonNode deadLetters = body(get("/admin/dead-letters"));
        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.get(0).get("last_error").asText()).contains("manual reconciliation");
    }

    @Test
    @DisplayName("a drainer that lost its lease does not overwrite the new owner's bookkeeping")
    void completionRequiresStillHoldingTheLease() {
        setFailureRate(0.0);
        UUID paymentId = authorizeAndCapture();

        List<OutboxItem> claimed = outbox.claimBatch(10, "owner-a", Duration.ofSeconds(30));
        assertThat(claimed).hasSize(1);
        long outboxId = claimed.getFirst().id();

        // Owner A's lease expires and owner B takes over.
        jdbc.update("UPDATE settlement_outbox SET lease_expires_at = now() - interval '1 second' WHERE id = ?",
                outboxId);
        outbox.reclaimExpiredLeases();
        List<OutboxItem> reclaimedByB = outbox.claimBatch(10, "owner-b", Duration.ofSeconds(30));
        assertThat(reclaimedByB).hasSize(1);

        // Owner A finally comes back and tries to record its result.
        assertThat(outbox.markSettled(outboxId, "owner-a"))
                .as("a stale owner must not be able to complete the item")
                .isZero();
        assertThat(outbox.scheduleRetry(outboxId, "owner-a", java.time.Instant.now(), "stale")).isZero();
        assertThat(outbox.markDeadLetter(outboxId, "owner-a", "stale")).isZero();

        assertThat(settlementStatus(paymentId)).isEqualTo("IN_FLIGHT");
        assertThat(outbox.markSettled(outboxId, "owner-b"))
                .as("the current owner can still complete it")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Leaves every pending item IN_FLIGHT under a dead owner with an expired lease. */
    private void strandInFlight(String deadOwner) {
        List<OutboxItem> claimed = outbox.claimBatch(100, deadOwner, Duration.ofMillis(1));
        assertThat(claimed).isNotEmpty();
        jdbc.update("UPDATE settlement_outbox SET lease_expires_at = now() - interval '1 second' "
                + "WHERE lease_owner = ?", deadOwner);
    }

    private UUID settlementKeyOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT settlement_key FROM settlement_outbox WHERE payment_id = ?",
                UUID.class, paymentId);
    }

    private ConfigurableApplicationContext startSecondInstance(Map<String, Object> overrides) {
        TestDatabase.Handle db = TestDatabase.get();
        Map<String, Object> properties = new java.util.LinkedHashMap<>(Map.of(
                "server.port", 0,
                "spring.datasource.url", db.jdbcUrl(),
                "spring.datasource.username", db.username(),
                "spring.datasource.password", db.password(),
                // The schema is already migrated by the primary context.
                "spring.flyway.enabled", false,
                "app.drain.enabled", false,
                "app.mock-downstream.failure-rate", 0.0));
        properties.putAll(overrides);

        // Passed as command-line arguments, not via builder.properties(): the
        // latter registers them as *default* properties, which rank below
        // application.yml and would leave this instance pointed at the yml's
        // default database rather than the test one.
        String[] args = properties.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(SettlementApplication.class).run(args);
    }

    /** Calls drain() on another application context without importing its beans into ours. */
    private record SettlementDrainerHandle(ConfigurableApplicationContext context) {
        void drain() {
            context.getBean(com.settlement.service.SettlementDrainer.class).drain();
        }
    }
}
