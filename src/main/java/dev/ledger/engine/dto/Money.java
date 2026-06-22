package dev.ledger.engine.dto;

/** Shared money bounds. Minor units (paise/cents); never floating point. */
public final class Money {

    /**
     * Maximum amount accepted on a single transfer/deposit. Capped far below
     * {@link Long#MAX_VALUE} (~9.2e18) so that even a long-lived account summing
     * many legs cannot overflow a {@code long} balance — 1e15 minor units is
     * 10 trillion major units, more than any realistic single movement.
     */
    public static final long MAX_AMOUNT_MINOR = 1_000_000_000_000_000L;

    private Money() {
    }
}
