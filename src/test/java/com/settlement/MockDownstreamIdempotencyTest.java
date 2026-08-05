package com.settlement;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.settlement.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole retry strategy rests on the provider being idempotent on the
 * settlement key. That assumption is load-bearing, so it gets tested directly
 * rather than assumed — including under concurrency, which is where a naive
 * check-then-insert implementation would quietly settle twice.
 */
class MockDownstreamIdempotencyTest extends IntegrationTest {

    @Test
    @DisplayName("re-posting a settlement key returns the first result and never settles twice")
    void repeatedSettlementKeyIsDeduplicated() {
        setFailureRate(0.0);
        UUID settlementKey = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Map<String, Object> request = Map.of(
                "settlement_key", settlementKey, "payment_id", paymentId,
                "amount_minor", 5_000L, "currency", "INR");

        ResponseEntity<String> first = post("/mock-settlement", request);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        String providerRef = body(first).get("provider_ref").asText();
        assertThat(body(first).get("duplicate").asBoolean()).isFalse();

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> repeat = post("/mock-settlement", request);
            assertThat(repeat.getStatusCode().value()).isEqualTo(200);
            assertThat(body(repeat).get("provider_ref").asText()).isEqualTo(providerRef);
            assertThat(body(repeat).get("duplicate").asBoolean()).isTrue();
        }

        assertThat(count("SELECT count(*) FROM mock_settlements WHERE settlement_key = ?", settlementKey))
                .isEqualTo(1);
        assertThat(count("SELECT delivery_count FROM mock_settlements WHERE settlement_key = ?", settlementKey))
                .as("the provider counted six deliveries and settled once")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("concurrent deliveries of one settlement key settle exactly once")
    void concurrentDeliveriesOfOneKeySettleOnce() {
        setFailureRate(0.0);
        UUID settlementKey = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Map<String, Object> request = Map.of(
                "settlement_key", settlementKey, "payment_id", paymentId,
                "amount_minor", 5_000L, "currency", "INR");

        List<ResponseEntity<String>> responses = inParallel(16, () -> post("/mock-settlement", request));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(200));
        Set<String> providerRefs = responses.stream()
                .map(r -> body(r).get("provider_ref").asText())
                .collect(Collectors.toSet());
        assertThat(providerRefs)
                .as("all sixteen callers must be told about the same single settlement")
                .hasSize(1);
        assertThat(count("SELECT count(*) FROM mock_settlements WHERE settlement_key = ?", settlementKey))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a failed settlement records nothing, so a retry can still settle it")
    void failedSettlementLeavesNothingBehind() {
        setFailureRate(1.0);
        UUID settlementKey = UUID.randomUUID();
        Map<String, Object> request = Map.of(
                "settlement_key", settlementKey, "payment_id", UUID.randomUUID(),
                "amount_minor", 5_000L, "currency", "INR");

        assertThat(post("/mock-settlement", request).getStatusCode().value()).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM mock_settlements WHERE settlement_key = ?", settlementKey))
                .as("a 500 must mean the money did not move")
                .isZero();

        setFailureRate(0.0);
        assertThat(post("/mock-settlement", request).getStatusCode().value()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM mock_settlements WHERE settlement_key = ?", settlementKey))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the configured fault rate is roughly what the downstream actually does")
    void faultInjectionRateIsHonest() {
        setFailureRate(0.4);

        List<Integer> statuses = inParallel(200, () -> post("/mock-settlement", Map.of(
                "settlement_key", UUID.randomUUID(), "payment_id", UUID.randomUUID(),
                "amount_minor", 100L, "currency", "INR")).getStatusCode().value());

        long failures = statuses.stream().filter(s -> s == 500).count();
        // Binomial(200, 0.4) has a standard deviation of ~6.9, so a window of
        // 15%-65% is about seven sigma wide: wide enough never to flake, narrow
        // enough to catch fault injection being broken or switched off.
        assertThat(failures).isBetween(30L, 130L);
    }
}
