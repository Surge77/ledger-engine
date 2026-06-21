package dev.ledger.engine.service;

import dev.ledger.engine.dto.BalanceResponse;
import java.util.List;

/** Outcome of a posted transaction: its id and the balances of the touched accounts. */
public record PostResult(long transactionId, List<BalanceResponse> balances) {
}
