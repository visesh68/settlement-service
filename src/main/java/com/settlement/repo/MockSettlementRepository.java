package com.settlement.repo;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The ledger of the SIMULATED DOWNSTREAM PROVIDER. Conceptually this lives on
 * the other side of the network and is not ours; it shares our database only
 * because the exercise asks the fake provider to live in the same app.
 *
 * <p>{@code settlement_key} is the primary key, so the provider cannot settle
 * one key twice no matter how many times we deliver it — which is precisely the
 * property a real payment provider gives you in exchange for an idempotency
 * key, and precisely the property our retry strategy leans on.
 */
@Repository
public class MockSettlementRepository {

    private static final RowMapper<MockSettlement> MAPPER = (rs, i) -> new MockSettlement(
            rs.getObject("settlement_key", UUID.class),
            rs.getObject("payment_id", UUID.class),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("provider_ref"),
            rs.getInt("delivery_count"),
            rs.getTimestamp("settled_at").toInstant());

    private final JdbcTemplate jdbc;

    public MockSettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * If this key was already settled, count the redelivery and return the
     * original result. The increment and the read are one statement, so
     * concurrent redeliveries are counted accurately.
     */
    public Optional<MockSettlement> recordRedelivery(UUID settlementKey) {
        return jdbc.query("""
                UPDATE mock_settlements
                   SET delivery_count = delivery_count + 1
                 WHERE settlement_key = ?
                RETURNING settlement_key, payment_id, amount_minor, currency,
                          provider_ref, delivery_count, settled_at
                """, MAPPER, settlementKey).stream().findFirst();
    }

    /**
     * First successful settlement for this key wins. Returns empty when another
     * concurrent caller inserted first, so the loser can go and read that one.
     */
    public Optional<MockSettlement> insertIfAbsent(UUID settlementKey, UUID paymentId,
                                                   long amountMinor, String currency, String providerRef) {
        return jdbc.query("""
                INSERT INTO mock_settlements
                    (settlement_key, payment_id, amount_minor, currency, provider_ref)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (settlement_key) DO NOTHING
                RETURNING settlement_key, payment_id, amount_minor, currency,
                          provider_ref, delivery_count, settled_at
                """, MAPPER, settlementKey, paymentId, amountMinor, currency, providerRef)
                .stream().findFirst();
    }

    public Optional<MockSettlement> find(UUID settlementKey) {
        return jdbc.query("""
                SELECT settlement_key, payment_id, amount_minor, currency,
                       provider_ref, delivery_count, settled_at
                  FROM mock_settlements WHERE settlement_key = ?
                """, MAPPER, settlementKey).stream().findFirst();
    }

    /** Distinct settlement keys the provider has settled. The gate compares this to captures. */
    public long distinctSettlementCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM mock_settlements", Long.class);
        return n == null ? 0L : n;
    }

    /** Total deliveries the provider received, including duplicates it deduped away. */
    public long totalDeliveries() {
        Long n = jdbc.queryForObject("SELECT COALESCE(sum(delivery_count), 0) FROM mock_settlements", Long.class);
        return n == null ? 0L : n;
    }

    /** How many payments the provider has settled money for, counted independently of us. */
    public long distinctPaymentCount() {
        Long n = jdbc.queryForObject("SELECT count(DISTINCT payment_id) FROM mock_settlements", Long.class);
        return n == null ? 0L : n;
    }

    /**
     * Payments the provider settled more than once. This is the direct negative
     * test for the headline invariant, asked of the provider's own ledger rather
     * than of our bookkeeping — so it stays honest even if our state is wrong.
     * It must always be zero.
     */
    public long doubleSettledPaymentCount() {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT payment_id FROM mock_settlements
                     GROUP BY payment_id HAVING count(*) > 1
                ) doubles
                """, Long.class);
        return n == null ? 0L : n;
    }

    public record MockSettlement(
            UUID settlementKey,
            UUID paymentId,
            long amountMinor,
            String currency,
            String providerRef,
            int deliveryCount,
            Instant settledAt) {
    }
}
