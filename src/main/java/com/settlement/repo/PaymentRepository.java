package com.settlement.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.settlement.domain.Payment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAuthorized(UUID id, String name, long amountMinor, String currency, String correlationId) {
        jdbc.update("""
                INSERT INTO payments (id, name, amount_minor, currency, state, correlation_id)
                VALUES (?, ?, ?, ?, 'AUTHORIZED', ?)
                """, id, name, amountMinor, currency, correlationId);
    }

    public Optional<Payment> findById(UUID id) {
        return jdbc.query("SELECT * FROM payments WHERE id = ?", RowMappers.PAYMENT, id)
                .stream().findFirst();
    }

    /**
     * Locks the payment row for the remainder of the transaction.
     *
     * <p>This is the serialization point for capture. Every concurrent capture
     * of the same payment queues here, so the read-check-write sequence that
     * follows executes one caller at a time. Under READ COMMITTED, statements
     * issued after the lock is granted take a fresh snapshot, so a caller that
     * waited here observes the winner's committed capture and its committed
     * idempotency record — which is exactly what makes the replay check
     * correct rather than racy.
     */
    public Optional<Payment> findByIdForUpdate(UUID id) {
        return jdbc.query("SELECT * FROM payments WHERE id = ? FOR UPDATE", RowMappers.PAYMENT, id)
                .stream().findFirst();
    }

    /**
     * Conditional transition. The {@code WHERE state = 'AUTHORIZED'} guard means
     * the database itself refuses a second capture; a return of 0 proves someone
     * else already moved the payment out of AUTHORIZED.
     */
    public int markCaptured(UUID id) {
        return jdbc.update("""
                UPDATE payments
                   SET state = 'CAPTURED', captured_at = now(), version = version + 1
                 WHERE id = ? AND state = 'AUTHORIZED'
                """, id);
    }

    public int markFailed(UUID id) {
        return jdbc.update("""
                UPDATE payments
                   SET state = 'FAILED', failed_at = now(), version = version + 1
                 WHERE id = ? AND state = 'AUTHORIZED'
                """, id);
    }

    public List<Payment> findRecent(int limit) {
        return jdbc.query("SELECT * FROM payments ORDER BY created_at DESC, id LIMIT ?",
                RowMappers.PAYMENT, limit);
    }

    public long countByState(String state) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM payments WHERE state = ?", Long.class, state);
        return n == null ? 0L : n;
    }
}
