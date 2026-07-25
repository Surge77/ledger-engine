package dev.ledger.engine.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

    /**
     * @param publisher how drained events are shipped: {@code log} (default, no broker
     *                  required) or {@code kafka}
     */
    public record Outbox(
            @Min(100) long pollIntervalMs,
            @Min(1) int batchSize,
            @Pattern(regexp = "log|kafka", message = "ledger.outbox.publisher must be 'log' or 'kafka'")
            String publisher,
            @Min(100) long sendTimeoutMs) {
    }

    public record Reconciliation(@Min(1000) long intervalMs) {
    }
}
