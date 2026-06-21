package dev.ledger.engine.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Single typed source for ledger config. Startup fails fast (clear message) when
 * a required value is missing — no raw env reads anywhere else in the codebase.
 */
@Validated
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        @NotBlank(message = "ledger.api-key (env LEDGER_API_KEY) must be set") String apiKey,
        Outbox outbox,
        Reconciliation reconciliation) {

    public record Outbox(@Min(100) long pollIntervalMs, @Min(1) int batchSize) {
    }

    public record Reconciliation(@Min(1000) long intervalMs) {
    }
}
