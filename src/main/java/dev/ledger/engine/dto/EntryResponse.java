package dev.ledger.engine.dto;

import dev.ledger.engine.domain.Entry;
import java.time.Instant;

public record EntryResponse(
        long id,
        long transactionId,
        long accountId,
        long amountMinor,
        String direction,
        String currency,
        Instant createdAt) {

    public static EntryResponse from(Entry e) {
        return new EntryResponse(e.id(), e.transactionId(), e.accountId(), e.amountMinor(),
                e.direction().name(), e.currency(), e.createdAt());
    }
}
