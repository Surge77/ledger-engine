package dev.ledger.engine.dto;

public record ReversalResponse(long reversalId, long originalTransferId, String status) {
}
