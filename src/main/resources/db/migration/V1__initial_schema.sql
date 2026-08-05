-- =============================================================================
-- V1 - payment capture + exactly-once settlement schema
--
-- Money is always integer minor units (paise/cents). There is no floating
-- point anywhere in this schema.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- payments
--
-- State machine:
--     (new) --authorize--> AUTHORIZED --capture--> CAPTURED   (terminal)
--                                     \--void----> FAILED     (terminal)
--
-- Only AUTHORIZED is capturable; the transition guard lives in the UPDATE's
-- WHERE clause as well as in application code, so the database refuses a
-- double capture even if the service logic is wrong.
-- -----------------------------------------------------------------------------
CREATE TABLE payments (
    id             UUID        PRIMARY KEY,
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency       VARCHAR(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    state          TEXT        NOT NULL CHECK (state IN ('AUTHORIZED', 'CAPTURED', 'FAILED')),
    version        BIGINT      NOT NULL DEFAULT 0,
    correlation_id TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    authorized_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    captured_at    TIMESTAMPTZ,
    failed_at      TIMESTAMPTZ,

    -- A payment is captured at most once: captured_at is set exactly when the
    -- state becomes CAPTURED and never cleared.
    CONSTRAINT payments_captured_at_matches_state
        CHECK ((state = 'CAPTURED') = (captured_at IS NOT NULL))
);

CREATE INDEX idx_payments_state ON payments (state);

-- -----------------------------------------------------------------------------
-- idempotency_records
--
-- One row per (scope, idempotency_key). The PRIMARY KEY is the serialization
-- point for retries of the same key: a concurrent duplicate INSERT blocks on
-- the uncommitted key and then sees the committed row.
--
-- request_fingerprint is a SHA-256 over the canonical request, so replaying a
-- key with a different body is detectable and answered with 409.
-- -----------------------------------------------------------------------------
CREATE TABLE idempotency_records (
    scope               TEXT        NOT NULL CHECK (scope IN ('authorize', 'capture')),
    idempotency_key     TEXT        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    request_fingerprint TEXT        NOT NULL,
    payment_id          UUID        REFERENCES payments (id),
    response_status     INT,
    response_body       JSONB,
    correlation_id      TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (scope, idempotency_key)
);

-- -----------------------------------------------------------------------------
-- settlement_outbox
--
-- Append-only work queue written in the SAME transaction as the capture, so a
-- captured payment always has exactly one settlement job and a rolled-back
-- capture leaves none (transactional outbox).
--
--   * payment_id     UNIQUE -> at most one settlement job per payment.
--   * settlement_key UNIQUE -> the dedupe key presented to the downstream. It
--                              is generated once, at capture time, and never
--                              regenerated on retry. This is what makes retries
--                              safe and settlement effectively-once.
--
-- Status machine:
--     PENDING --claim--> IN_FLIGHT --2xx--------> SETTLED      (terminal)
--                            |     \--5xx/timeout--> PENDING   (backoff retry)
--                            |                    \-> DEAD_LETTER (attempts exhausted, terminal)
--                            \--lease expiry (crash)--> PENDING
-- -----------------------------------------------------------------------------
CREATE TABLE settlement_outbox (
    id               BIGSERIAL   PRIMARY KEY,
    payment_id       UUID        NOT NULL UNIQUE REFERENCES payments (id),
    settlement_key   UUID        NOT NULL UNIQUE,
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency         VARCHAR(3)  NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SETTLED', 'DEAD_LETTER')),
    attempts         INT         NOT NULL DEFAULT 0,
    max_attempts     INT         NOT NULL,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner      TEXT,
    lease_expires_at TIMESTAMPTZ,
    last_error       TEXT,
    correlation_id   TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    first_attempt_at TIMESTAMPTZ,
    settled_at       TIMESTAMPTZ,

    CONSTRAINT outbox_settled_at_matches_status
        CHECK ((status = 'SETTLED') = (settled_at IS NOT NULL))
);

-- Partial index driving the claim query: only PENDING rows are ever scanned.
CREATE INDEX idx_outbox_claimable
    ON settlement_outbox (next_attempt_at, id)
    WHERE status = 'PENDING';

-- Partial index driving the lease reaper.
CREATE INDEX idx_outbox_expired_leases
    ON settlement_outbox (lease_expires_at)
    WHERE status = 'IN_FLIGHT';

-- -----------------------------------------------------------------------------
-- mock_settlements
--
-- This table belongs to the SIMULATED DOWNSTREAM PROVIDER, not to us. It exists
-- so the fake provider can be genuinely idempotent on the settlement key and so
-- the correctness gate can count real settlements.
--
--   * settlement_key is the PRIMARY KEY  -> the provider physically cannot
--     settle the same key twice, whatever we do.
--   * delivery_count records how many times we delivered that key, which is how
--     the tests demonstrate "at-least-once delivery + provider dedupe =
--     effectively-once settlement" rather than merely asserting it.
--
-- Only SUCCESSFUL settlements are recorded. An injected 500 means "not settled",
-- so a retry is still free to settle it.
-- -----------------------------------------------------------------------------
CREATE TABLE mock_settlements (
    settlement_key UUID        PRIMARY KEY,
    payment_id     UUID        NOT NULL,
    amount_minor   BIGINT      NOT NULL,
    currency       VARCHAR(3)  NOT NULL,
    provider_ref   TEXT        NOT NULL,
    delivery_count INT         NOT NULL DEFAULT 1,
    settled_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
