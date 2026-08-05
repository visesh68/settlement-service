package com.settlement.repo;

import java.util.Optional;
import java.util.UUID;

import com.settlement.domain.IdempotencyRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Stores one row per (scope, idempotency key) holding the fingerprint of the
 * request that first used it and the response that was returned. Replaying the
 * key returns that stored response byte-for-byte; replaying it with a different
 * body is a 409.
 *
 * <p>The composite PRIMARY KEY is load-bearing: a concurrent duplicate INSERT
 * blocks on the uncommitted key rather than racing past it, so the second
 * caller either sees the committed record or gets a duplicate-key error it can
 * translate into a replay.
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRecord> find(String scope, String key) {
        return jdbc.query("SELECT * FROM idempotency_records WHERE scope = ? AND idempotency_key = ?",
                RowMappers.IDEMPOTENCY, scope, key).stream().findFirst();
    }

    /**
     * Throws {@link org.springframework.dao.DuplicateKeyException} if the key is
     * already taken — the caller treats that as "someone else won, go replay".
     */
    public void insert(String scope, String key, String fingerprint, UUID paymentId,
                       int responseStatus, String responseBody, String correlationId) {
        jdbc.update("""
                INSERT INTO idempotency_records
                    (scope, idempotency_key, request_fingerprint, payment_id,
                     response_status, response_body, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, scope, key, fingerprint, paymentId, responseStatus, responseBody, correlationId);
    }
}
