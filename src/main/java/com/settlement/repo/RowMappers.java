package com.settlement.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import com.settlement.domain.IdempotencyRecord;
import com.settlement.domain.OutboxItem;
import com.settlement.domain.Payment;
import com.settlement.domain.PaymentState;
import com.settlement.domain.SettlementStatus;
import org.springframework.jdbc.core.RowMapper;

final class RowMappers {

    private RowMappers() {
    }

    static final RowMapper<Payment> PAYMENT = (rs, i) -> new Payment(
            rs.getObject("id", java.util.UUID.class),
            rs.getString("name"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            PaymentState.valueOf(rs.getString("state")),
            rs.getLong("version"),
            rs.getString("correlation_id"),
            instant(rs, "created_at"),
            instant(rs, "authorized_at"),
            instant(rs, "captured_at"),
            instant(rs, "failed_at"));

    static final RowMapper<OutboxItem> OUTBOX = (rs, i) -> new OutboxItem(
            rs.getLong("id"),
            rs.getObject("payment_id", java.util.UUID.class),
            rs.getObject("settlement_key", java.util.UUID.class),
            rs.getString("name"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            SettlementStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            rs.getInt("max_attempts"),
            instant(rs, "next_attempt_at"),
            rs.getString("lease_owner"),
            instant(rs, "lease_expires_at"),
            rs.getString("last_error"),
            rs.getString("correlation_id"),
            instant(rs, "created_at"),
            instant(rs, "first_attempt_at"),
            instant(rs, "settled_at"));

    static final RowMapper<IdempotencyRecord> IDEMPOTENCY = (rs, i) -> new IdempotencyRecord(
            rs.getString("scope"),
            rs.getString("idempotency_key"),
            rs.getString("request_fingerprint"),
            rs.getObject("payment_id", java.util.UUID.class),
            rs.getInt("response_status"),
            rs.getString("response_body"),
            rs.getString("correlation_id"),
            instant(rs, "created_at"));

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
