package com.settlement.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.settlement.domain.OutboxItem;
import com.settlement.domain.SettlementStatus;
import com.settlement.repo.MockSettlementRepository;
import com.settlement.repo.OutboxRepository;
import com.settlement.repo.PaymentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Computes the correctness invariants directly from the database so the gate
 * scripts can assert on them with one request instead of reconstructing them
 * from a pile of individual payment lookups.
 *
 * <p>The invariants are deliberately cross-checked against the <em>provider's</em>
 * ledger, not just our own outbox: a bug in our bookkeeping cannot make these
 * numbers look good, because "how many payments did the provider actually settle"
 * is answered by the provider's table.
 */
@Service
public class StatsService {

    private final PaymentRepository payments;
    private final OutboxRepository outbox;
    private final MockSettlementRepository downstream;
    private final MockSettlementService mockDownstream;
    private final JdbcTemplate jdbc;

    public StatsService(PaymentRepository payments, OutboxRepository outbox,
                        MockSettlementRepository downstream, MockSettlementService mockDownstream,
                        JdbcTemplate jdbc) {
        this.payments = payments;
        this.outbox = outbox;
        this.downstream = downstream;
        this.mockDownstream = mockDownstream;
        this.jdbc = jdbc;
    }

    public Map<String, Object> snapshot() {
        long authorized = payments.countByState("AUTHORIZED");
        long captured = payments.countByState("CAPTURED");
        long failed = payments.countByState("FAILED");

        Map<String, Long> outboxCounts = outbox.countsByStatus();
        long outboxTotal = outboxCounts.values().stream().mapToLong(Long::longValue).sum();

        long distinctSettlementKeys = downstream.distinctSettlementCount();
        long distinctPaymentsSettled = downstream.distinctPaymentCount();
        long totalDeliveries = downstream.totalDeliveries();
        long doubleSettled = downstream.doubleSettledPaymentCount();

        long capturedWithoutOutbox = queryLong("""
                SELECT count(*) FROM payments p
                 WHERE p.state = 'CAPTURED'
                   AND NOT EXISTS (SELECT 1 FROM settlement_outbox o WHERE o.payment_id = p.id)
                """);
        long settledButNotCaptured = queryLong("""
                SELECT count(*) FROM mock_settlements m
                  JOIN payments p ON p.id = m.payment_id
                 WHERE p.state <> 'CAPTURED'
                """);
        long outstanding = outboxCounts.getOrDefault("PENDING", 0L)
                + outboxCounts.getOrDefault("IN_FLIGHT", 0L);

        Map<String, Object> paymentCounts = new LinkedHashMap<>();
        paymentCounts.put("AUTHORIZED", authorized);
        paymentCounts.put("CAPTURED", captured);
        paymentCounts.put("FAILED", failed);
        paymentCounts.put("total", authorized + captured + failed);

        Map<String, Object> downstreamCounts = new LinkedHashMap<>();
        downstreamCounts.put("distinct_settlement_keys", distinctSettlementKeys);
        downstreamCounts.put("distinct_payments_settled", distinctPaymentsSettled);
        downstreamCounts.put("total_deliveries", totalDeliveries);
        downstreamCounts.put("redundant_deliveries", totalDeliveries - distinctSettlementKeys);
        downstreamCounts.put("failure_rate", mockDownstream.failureRate());

        long settledRows = outboxCounts.getOrDefault("SETTLED", 0L);
        long deadLettered = outboxCounts.getOrDefault("DEAD_LETTER", 0L);

        Map<String, Object> invariants = new LinkedHashMap<>();
        invariants.put("captured_payments", captured);
        invariants.put("outbox_rows", outboxTotal);
        invariants.put("captured_without_outbox_row", capturedWithoutOutbox);
        invariants.put("double_settled_payments", doubleSettled);
        invariants.put("settled_but_not_captured", settledButNotCaptured);
        invariants.put("settled_outbox_rows", settledRows);
        invariants.put("dead_lettered", deadLettered);
        invariants.put("still_outstanding", outstanding);

        // ---- Safety: true at every instant, mid-drain, mid-crash, always. ----
        // These are what "exactly once" actually means, and they are answered
        // from the provider's ledger, not ours.
        invariants.put("safety_holds",
                doubleSettled == 0                              // no payment settled twice
                        && capturedWithoutOutbox == 0           // no capture lost its settlement job
                        && settledButNotCaptured == 0           // nothing settled that was not captured
                        && distinctSettlementKeys == distinctPaymentsSettled); // one key per settled payment

        // ---- Liveness: true once the outbox has been drained to quiescence. ----
        invariants.put("fully_drained", outstanding == 0);
        invariants.put("converged", outstanding == 0 && settledRows + deadLettered == captured);

        // Settled downstream but not recorded settled by us. Non-zero only if an
        // instance died after the provider committed but before we did, and then
        // exhausted its attempts — the case dead-lettering exists to surface for
        // reconciliation. Normally zero.
        invariants.put("settled_downstream_not_recorded", distinctSettlementKeys - settledRows);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("payments", paymentCounts);
        snapshot.put("outbox", outboxCounts);
        snapshot.put("downstream", downstreamCounts);
        snapshot.put("invariants", invariants);
        return snapshot;
    }

    public List<OutboxItem> deadLetters(int limit) {
        return outbox.findByStatus(SettlementStatus.DEAD_LETTER, limit);
    }

    private long queryLong(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }
}
