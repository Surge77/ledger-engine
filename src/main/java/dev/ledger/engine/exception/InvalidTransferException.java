package dev.ledger.engine.exception;

import org.springframework.http.HttpStatus;

public class InvalidTransferException extends LedgerException {

    public InvalidTransferException(String message) {
        super("INVALID_TRANSFER", HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
