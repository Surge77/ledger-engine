package dev.ledger.engine.domain;

public record OutboxEvent(long id, long transactionId, String eventType, String payload) {
}
