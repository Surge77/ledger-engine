package dev.ledger.engine.exception;

import org.springframework.http.HttpStatus;

/** Base for domain errors that map to a known HTTP status + stable error code. */
public abstract class LedgerException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    protected LedgerException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
