package dev.ledger.engine.exception;

import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends LedgerException {

    public AccountNotFoundException(long accountId) {
        super("ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND, "account " + accountId + " does not exist");
    }
}
