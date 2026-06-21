package dev.ledger.engine.dto;

import java.time.Instant;
import java.util.List;

/**
 * Reconciliation outcome. {@code invariantOk} is true when the global entry sum
 * is zero and no transaction is unbalanced — i.e. zero drift.
 */
public record ReconcileResult(
        boolean invariantOk,
        long entriesSum,
        List<Long> unbalancedTransactions,
        Instant checkedAt) {
}
