package com.settlement.metrics;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.settlement.service.StatsService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The safety invariants, exposed as gauges.
 *
 * <p>{@link AppMetrics} measures throughput and latency — how much work happened
 * and how fast. None of that says whether the work was <em>correct</em>. These
 * gauges carry the properties the service actually promises, so "no payment was
 * ever settled twice" becomes something a scrape can alert on rather than a
 * number a human has to go and read out of {@code /admin/stats}.
 *
 * <p>Like outbox depth, these are polled state rather than events, so they are
 * gauges on a schedule rather than counters. The poll is deliberately slower
 * than the outbox one: it runs several aggregate queries, and an invariant that
 * is violated stays violated — catching it ten seconds later costs nothing.
 *
 * <p>A failed refresh leaves the previous values in place rather than zeroing
 * them, because a database blip must not be indistinguishable from "everything
 * is fine". Pair any alert on {@code settlement_safety_holds} with a staleness
 * or {@code up} check, or the alert goes quiet at exactly the wrong moment.
 */
@Component
public class InvariantMetrics {

    private final StatsService stats;

    private final AtomicLong doubleSettled = new AtomicLong();
    private final AtomicLong capturedWithoutOutbox = new AtomicLong();
    private final AtomicLong settledButNotCaptured = new AtomicLong();
    private final AtomicLong settledDownstreamNotRecorded = new AtomicLong();
    private final AtomicLong redundantDeliveries = new AtomicLong();
    private final AtomicLong distinctSettlementKeys = new AtomicLong();
    private final AtomicLong totalDeliveries = new AtomicLong();
    private final AtomicLong stillOutstanding = new AtomicLong();
    private final AtomicLong safetyHolds = new AtomicLong(1);
    private final AtomicLong converged = new AtomicLong();

    public InvariantMetrics(MeterRegistry registry, StatsService stats) {
        this.stats = stats;

        // ---- Safety. Every one of these must stay at zero. ----
        gauge(registry, "settlement.double.settled", doubleSettled,
                "Payments the provider settled more than once. Must always be zero.");
        gauge(registry, "settlement.captured.without.outbox", capturedWithoutOutbox,
                "Captured payments with no settlement job. Money owed and never queued.");
        gauge(registry, "settlement.settled.not.captured", settledButNotCaptured,
                "Settlements for payments that were never captured. Money moved for nothing.");

        // 1 when every safety condition holds, 0 the instant one does not.
        gauge(registry, "settlement.safety.holds", safetyHolds,
                "1 while all safety invariants hold, 0 if any is violated.");

        // ---- Evidence. Non-zero is healthy, not a problem. ----
        gauge(registry, "settlement.redundant.deliveries", redundantDeliveries,
                "Deliveries the provider deduplicated. Proof of effectively-once, not an error.");
        gauge(registry, "settlement.distinct.keys", distinctSettlementKeys,
                "Distinct settlement keys the provider has settled.");
        gauge(registry, "settlement.total.deliveries", totalDeliveries,
                "Total deliveries the provider received, including deduplicated ones.");

        // ---- Liveness. Transient non-zero is normal; persistent is not. ----
        gauge(registry, "settlement.outstanding", stillOutstanding,
                "Outbox rows still PENDING or IN_FLIGHT.");
        gauge(registry, "settlement.settled.downstream.not.recorded", settledDownstreamNotRecorded,
                "Settled by the provider but not recorded by us. Persistent non-zero needs reconciliation.");
        gauge(registry, "settlement.converged", converged,
                "1 when the outbox is drained and every capture reached a terminal state.");
    }

    private static void gauge(MeterRegistry registry, String name, AtomicLong value, String description) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .description(description)
                .register(registry);
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 3_000)
    public void refresh() {
        try {
            Map<String, Object> snapshot = stats.snapshot();
            Map<String, Object> invariants = section(snapshot, "invariants");
            Map<String, Object> downstream = section(snapshot, "downstream");

            doubleSettled.set(number(invariants.get("double_settled_payments")));
            capturedWithoutOutbox.set(number(invariants.get("captured_without_outbox_row")));
            settledButNotCaptured.set(number(invariants.get("settled_but_not_captured")));
            settledDownstreamNotRecorded.set(number(invariants.get("settled_downstream_not_recorded")));
            stillOutstanding.set(number(invariants.get("still_outstanding")));
            safetyHolds.set(flag(invariants.get("safety_holds")));
            converged.set(flag(invariants.get("converged")));

            redundantDeliveries.set(number(downstream.get("redundant_deliveries")));
            distinctSettlementKeys.set(number(downstream.get("distinct_settlement_keys")));
            totalDeliveries.set(number(downstream.get("total_deliveries")));

        } catch (RuntimeException ignored) {
            // Keep the last known values. A metrics refresh must never take the
            // process down, and must never invent a reassuring zero.
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> snapshot, String key) {
        return (Map<String, Object>) snapshot.get(key);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static long flag(Object value) {
        return Boolean.TRUE.equals(value) ? 1L : 0L;
    }
}
