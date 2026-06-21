package dev.ledger.engine.dto;

public record BalanceResponse(long account, long balanceMinor, String currency) {
}
