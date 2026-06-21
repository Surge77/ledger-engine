package dev.ledger.engine.exception;

import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends LedgerException {

    public InsufficientFundsException(long accountId, long balanceMinor, long amountMinor) {
        super("INSUFFICIENT_FUNDS", HttpStatus.UNPROCESSABLE_ENTITY,
                "account " + accountId + " balance " + balanceMinor + " < requested " + amountMinor);
    }
}
