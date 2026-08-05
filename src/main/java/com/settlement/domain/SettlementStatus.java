package com.settlement.domain;

/**
 * Lifecycle of one outbox row.
 *
 * <pre>
 *   PENDING --claim--> IN_FLIGHT --2xx------------> SETTLED       (terminal)
 *                          |     \--5xx/timeout---> PENDING       (backoff)
 *                          |                     \-> DEAD_LETTER  (attempts exhausted, terminal)
 *                          \--lease expiry (owner crashed)--> PENDING
 * </pre>
 *
 * There is no "lost" state by construction: every row is in exactly one of
 * these four, and only SETTLED and DEAD_LETTER are terminal. DEAD_LETTER is
 * loud and queryable — nothing is ever silently dropped.
 */
public enum SettlementStatus {
    PENDING,
    IN_FLIGHT,
    SETTLED,
    DEAD_LETTER;

    public boolean isTerminal() {
        return this == SETTLED || this == DEAD_LETTER;
    }
}
