package dev.ledger.engine.exception;

import org.springframework.http.HttpStatus;

public class TransactionNotFoundException extends LedgerException {

    public TransactionNotFoundException(long txId) {
        super("TRANSACTION_NOT_FOUND", HttpStatus.NOT_FOUND, "transaction " + txId + " does not exist");
    }
}
