package dev.ledger.engine.exception;

import org.springframework.http.HttpStatus;

public class CurrencyMismatchException extends LedgerException {

    public CurrencyMismatchException(String message) {
        super("CURRENCY_MISMATCH", HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
