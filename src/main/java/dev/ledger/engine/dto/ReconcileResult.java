package dev.ledger.engine.dto;

import java.time.Instant;
import java.util.List;

/**
 * Reconciliation outcome. {@code invariantOk} is true when the global entry sum
 * is zero and no transaction is unbalanced — i.e. zero drift. {@code truncated}
 * is true when more unbalanced transactions exist than were listed (the drift
 * report is capped so an incident can't return an unbounded result set).
 */
public record ReconcileResult(
        boolean invariantOk,
        long entriesSum,
        List<Long> unbalancedTransactions,
        boolean truncated,
        Instant checkedAt) {
}
