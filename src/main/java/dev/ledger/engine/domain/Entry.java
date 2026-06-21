package dev.ledger.engine.domain;

import java.time.Instant;

public record Entry(
        long id,
        long transactionId,
        long accountId,
        long amountMinor,
        EntryDirection direction,
        String currency,
        Instant createdAt) {
}
