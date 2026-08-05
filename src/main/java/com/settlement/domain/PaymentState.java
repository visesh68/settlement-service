package com.settlement.domain;

/**
 * <pre>
 *   (new) --authorize--> AUTHORIZED --capture--> CAPTURED  (terminal)
 *                                   \--void----> FAILED    (terminal)
 * </pre>
 *
 * Only {@link #AUTHORIZED} is capturable. Both terminal states reject capture,
 * which is what makes "an already-captured or failed payment cannot be
 * re-captured" true rather than aspirational.
 */
public enum PaymentState {
    AUTHORIZED,
    CAPTURED,
    FAILED;

    public boolean isCapturable() {
        return this == AUTHORIZED;
    }
}
