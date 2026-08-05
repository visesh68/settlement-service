package com.settlement;

import java.util.Map;
import java.util.UUID;

import com.settlement.metrics.InvariantMetrics;
import com.settlement.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The safety invariants as metrics.
 *
 * <p>These exist so the core guarantee is alertable rather than only visible to
 * a human reading {@code /admin/stats}. That makes the exported <em>names</em>
 * part of the contract: a Grafana dashboard queries them by string, and renaming
 * a meter would blank a panel silently rather than fail a build. The exposition
 * test below is what turns that into a compile-time-ish guarantee.
 */
class InvariantMetricsTest extends IntegrationTest {

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private InvariantMetrics invariants;

    @Test
    @DisplayName("gauges mirror what /admin/stats reports")
    void gaugesMirrorTheStatsSnapshot() {
        UUID paymentId = authorizeAndCapture();
        assertThat(drainUntilQuiet(30)).isTrue();
        invariants.refresh();

        var stats = stats();
        var i = stats.get("invariants");
        var d = stats.get("downstream");

        assertThat(gauge("settlement.safety.holds"))
                .as("1 while safety holds")
                .isEqualTo(i.get("safety_holds").asBoolean() ? 1.0 : 0.0);
        assertThat(gauge("settlement.double.settled"))
                .isEqualTo((double) i.get("double_settled_payments").asLong());
        assertThat(gauge("settlement.distinct.keys"))
                .isEqualTo((double) d.get("distinct_settlement_keys").asLong());
        assertThat(gauge("settlement.total.deliveries"))
                .isEqualTo((double) d.get("total_deliveries").asLong());
        assertThat(gauge("settlement.redundant.deliveries"))
                .isEqualTo((double) d.get("redundant_deliveries").asLong());

        assertThat(paymentId).isNotNull();
    }

    @Test
    @DisplayName("a deduplicated redelivery moves the redundant-deliveries gauge, not the safety one")
    void redeliveryMovesEvidenceNotSafety() {
        UUID paymentId = authorizeAndCapture();
        assertThat(drainUntilQuiet(30)).isTrue();

        invariants.refresh();
        double redundantBefore = gauge("settlement.redundant.deliveries");

        UUID settlementKey = UUID.fromString(
                body(get("/payments/" + paymentId)).get("settlement_key").asText());

        // Exactly what a crashed instance or a post-commit timeout does.
        var response = post("/mock-settlement", Map.of(
                "settlement_key", settlementKey.toString(),
                "payment_id", paymentId.toString(),
                "amount_minor", 125_000L,
                "currency", "INR"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body(response).get("duplicate").asBoolean()).isTrue();

        invariants.refresh();

        assertThat(gauge("settlement.redundant.deliveries"))
                .as("the dedupe is counted as evidence")
                .isGreaterThan(redundantBefore);
        assertThat(gauge("settlement.double.settled"))
                .as("redelivering a settled key must never count as a double settlement")
                .isZero();
        assertThat(gauge("settlement.safety.holds"))
                .as("safety is unaffected by redelivery — that is the whole point")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("/metrics exposes the exact names the Grafana dashboard queries")
    void metricsEndpointExposesTheDashboardsNames() {
        invariants.refresh();
        String exposition = get("/metrics").getBody();

        // Pinned deliberately. These strings are duplicated in
        // grafana/settlement-service-dashboard.json; renaming a meter without
        // updating the dashboard would blank a panel in silence, so the build
        // fails here instead.
        assertThat(exposition)
                .contains("settlement_safety_holds")
                .contains("settlement_double_settled")
                .contains("settlement_captured_without_outbox")
                .contains("settlement_settled_not_captured")
                .contains("settlement_redundant_deliveries")
                .contains("settlement_distinct_keys")
                .contains("settlement_total_deliveries")
                .contains("settlement_outstanding")
                .contains("settlement_settled_downstream_not_recorded")
                .contains("settlement_converged")
                .contains("settlement_outbox_size")
                .contains("settlement_call_duration_seconds")
                .contains("settlement_dead_letter_total")
                .contains("idempotency_replay_total");
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
