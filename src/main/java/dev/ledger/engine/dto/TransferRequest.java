package dev.ledger.engine.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
        @NotNull(message = "from is required") Long from,
        @NotNull(message = "to is required") Long to,
        @NotNull(message = "amountMinor is required")
        @Positive(message = "amountMinor must be greater than 0") Long amountMinor,
        @NotNull(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter uppercase ISO code")
        String currency) {
}
