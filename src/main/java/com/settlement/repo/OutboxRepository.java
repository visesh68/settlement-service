package com.settlement.repo;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.settlement.domain.OutboxItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The transactional outbox.
 *
 * <p>Rows are inserted in the same transaction as the capture, so "captured"
 * and "will be settled" commit together or not at all. From then on the outbox
 * is the single source of truth for settlement work, and the process that
 * happens to be running is irrelevant — which is what makes a mid-drain crash
 * a non-event.
 */
@Repository
public class OutboxRepository {

    private static final String COLUMNS = """
            id, payment_id, settlement_key, name, amount_minor, currency, status, attempts,
            max_attempts, next_attempt_at, lease_owner, lease_expires_at, last_error,
            correlation_id, created_at, first_attempt_at, settled_at
            """;

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Enqueue exactly one settlement job for a capture. Called inside the
     * capture transaction. {@code payment_id} is UNIQUE, so even a logic bug
     * upstream cannot produce two settlement jobs for one payment.
     *
     * <p>The settlement key is minted here, once, and is never regenerated on
     * retry. That single fact is what makes every later redelivery safe.
     */
    public void enqueue(UUID paymentId, UUID settlementKey, String name, long amountMinor, String currency,
                        int maxAttempts, String correlationId) {
        jdbc.update("""
                INSERT INTO settlement_outbox
                    (payment_id, settlement_key, name, amount_minor, currency, status,
                     attempts, max_attempts, next_attempt_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, now(), ?)
                """, paymentId, settlementKey, name, amountMinor, currency, maxAttempts, correlationId);
    }

    public Optional<OutboxItem> findByPaymentId(UUID paymentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM settlement_outbox WHERE payment_id = ?",
                RowMappers.OUTBOX, paymentId).stream().findFirst();
    }

    public Optional<OutboxItem> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM settlement_outbox WHERE id = ?",
                RowMappers.OUTBOX, id).stream().findFirst();
    }

    /**
     * Atomically claim up to {@code batchSize} due items for this instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is the whole trick for concurrent
     * drainers: two drain passes running at the same moment take disjoint sets
     * of rows because each skips whatever the other has locked, rather than
     * blocking on it. No item is ever handed to two drainers in the same
     * instant, so there is no double-settle to deduplicate in the common case.
     *
     * <p>{@code attempts} is incremented at claim time, not at completion. If
     * this process dies mid-flight the attempt is still counted, so a request
     * that reliably kills the drainer cannot loop forever — it burns its
     * attempts and dead-letters.
     *
     * <p>The lease ({@code lease_owner} + {@code lease_expires_at}) is what a
     * crash leaves behind for the reaper to find.
     */
    public List<OutboxItem> claimBatch(int batchSize, String owner, Duration leaseDuration) {
        return jdbc.query("""
                UPDATE settlement_outbox o
                   SET status           = 'IN_FLIGHT',
                       attempts         = o.attempts + 1,
                       lease_owner      = ?,
                       lease_expires_at = now() + make_interval(secs => ?),
                       first_attempt_at = COALESCE(o.first_attempt_at, now()),
                       updated_at       = now()
                  FROM (
                       SELECT id
                         FROM settlement_outbox
                        WHERE status = 'PENDING'
                          AND next_attempt_at <= now()
                        ORDER BY next_attempt_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT ?
                  ) due
                 WHERE o.id = due.id
                RETURNING o.id, o.payment_id, o.settlement_key, o.name, o.amount_minor, o.currency,
                          o.status, o.attempts, o.max_attempts, o.next_attempt_at, o.lease_owner,
                          o.lease_expires_at, o.last_error, o.correlation_id, o.created_at,
                          o.first_attempt_at, o.settled_at
                """, RowMappers.OUTBOX, owner, (double) leaseDuration.toMillis() / 1000.0, batchSize);
    }

    /**
     * Mark settled, but only if we still hold the lease. If the lease expired
     * and another drainer took over, this returns 0 and we log a lost race
     * instead of stamping over the new owner's work. Either way the downstream
     * saw one settlement key and settled once.
     */
    public int markSettled(long id, String owner) {
        return jdbc.update("""
                UPDATE settlement_outbox
                   SET status = 'SETTLED', settled_at = now(), lease_owner = NULL,
                       lease_expires_at = NULL, last_error = NULL, updated_at = now()
                 WHERE id = ? AND status = 'IN_FLIGHT' AND lease_owner = ?
                """, id, owner);
    }

    /** Release the item back to PENDING with a backoff. */
    public int scheduleRetry(long id, String owner, Instant nextAttemptAt, String lastError) {
        return jdbc.update("""
                UPDATE settlement_outbox
                   SET status = 'PENDING', next_attempt_at = ?, lease_owner = NULL,
                       lease_expires_at = NULL, last_error = ?, updated_at = now()
                 WHERE id = ? AND status = 'IN_FLIGHT' AND lease_owner = ?
                """, java.sql.Timestamp.from(nextAttemptAt), truncate(lastError), id, owner);
    }

    /** Terminal failure. Visible, queryable, never silently dropped. */
    public int markDeadLetter(long id, String owner, String lastError) {
        return jdbc.update("""
                UPDATE settlement_outbox
                   SET status = 'DEAD_LETTER', lease_owner = NULL, lease_expires_at = NULL,
                       last_error = ?, updated_at = now()
                 WHERE id = ? AND status = 'IN_FLIGHT' AND lease_owner = ?
                """, truncate(lastError), id, owner);
    }

    /**
     * Recover work stranded by a crashed or hung drainer.
     *
     * <p>An IN_FLIGHT row whose lease has expired belongs to an owner that is
     * not coming back. We do not know whether its HTTP call reached the
     * downstream, and we do not need to: redelivering the same settlement key
     * is safe, because the downstream dedupes on it. Items with attempts left
     * go back to PENDING; items that already burned their attempts dead-letter
     * so they surface for a human instead of cycling.
     */
    public List<OutboxItem> reclaimExpiredLeases() {
        List<OutboxItem> requeued = jdbc.query("""
                UPDATE settlement_outbox o
                   SET status = 'PENDING', next_attempt_at = now(), lease_owner = NULL,
                       lease_expires_at = NULL, updated_at = now(),
                       last_error = 'lease expired; owner ' || COALESCE(o.lease_owner, '?') ||
                                    ' disappeared mid-flight, redelivering'
                 WHERE o.status = 'IN_FLIGHT'
                   AND o.lease_expires_at < now()
                   AND o.attempts < o.max_attempts
                RETURNING o.id, o.payment_id, o.settlement_key, o.name, o.amount_minor, o.currency,
                          o.status, o.attempts, o.max_attempts, o.next_attempt_at, o.lease_owner,
                          o.lease_expires_at, o.last_error, o.correlation_id, o.created_at,
                          o.first_attempt_at, o.settled_at
                """, RowMappers.OUTBOX);

        List<OutboxItem> exhausted = jdbc.query("""
                UPDATE settlement_outbox o
                   SET status = 'DEAD_LETTER', lease_owner = NULL, lease_expires_at = NULL,
                       updated_at = now(),
                       last_error = 'lease expired with no attempts remaining; needs manual reconciliation'
                 WHERE o.status = 'IN_FLIGHT'
                   AND o.lease_expires_at < now()
                   AND o.attempts >= o.max_attempts
                RETURNING o.id, o.payment_id, o.settlement_key, o.name, o.amount_minor, o.currency,
                          o.status, o.attempts, o.max_attempts, o.next_attempt_at, o.lease_owner,
                          o.lease_expires_at, o.last_error, o.correlation_id, o.created_at,
                          o.first_attempt_at, o.settled_at
                """, RowMappers.OUTBOX);

        return java.util.stream.Stream.concat(requeued.stream(), exhausted.stream()).toList();
    }

    public Map<String, Long> countsByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (com.settlement.domain.SettlementStatus s : com.settlement.domain.SettlementStatus.values()) {
            counts.put(s.name(), 0L);
        }
        jdbc.query("SELECT status, count(*) AS n FROM settlement_outbox GROUP BY status", rs -> {
            counts.put(rs.getString("status"), rs.getLong("n"));
        });
        return counts;
    }

    public List<OutboxItem> findByStatus(com.settlement.domain.SettlementStatus status, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM settlement_outbox WHERE status = ? ORDER BY id LIMIT ?",
                RowMappers.OUTBOX, status.name(), limit);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
