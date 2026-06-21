package dev.ledger.engine.dto;

import dev.ledger.engine.domain.Account;
import java.time.Instant;

public record AccountResponse(long id, String name, String currency, Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.id(), account.name(), account.currency(), account.createdAt());
    }
}
