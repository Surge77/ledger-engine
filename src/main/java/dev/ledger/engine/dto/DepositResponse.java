package dev.ledger.engine.dto;

/** Result of a deposit: the posting id, the credited account, and its new balance. */
public record DepositResponse(
        long transactionId,
        long accountId,
        long balanceMinor,
        String currency,
        String status) {
}
