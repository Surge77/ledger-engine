package dev.ledger.engine.domain;

import java.time.Instant;

public record Transaction(
        long id,
        String idempotencyKey,
        TransactionType type,
        TransactionStatus status,
        Long reversesTxId,
        Instant createdAt) {
}
