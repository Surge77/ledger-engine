package dev.ledger.engine.domain;

import java.time.Instant;

public record Account(long id, String name, String currency, Instant createdAt) {
}
