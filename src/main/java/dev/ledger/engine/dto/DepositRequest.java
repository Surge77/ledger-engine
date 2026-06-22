package dev.ledger.engine.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** Credits an account from its per-currency system account (money entering the ledger). */
public record DepositRequest(
        @NotNull(message = "amountMinor is required")
        @Positive(message = "amountMinor must be greater than 0")
        @Max(value = Money.MAX_AMOUNT_MINOR, message = "amountMinor exceeds the maximum allowed")
        Long amountMinor,
        @NotNull(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter uppercase ISO code")
        String currency) {
}
