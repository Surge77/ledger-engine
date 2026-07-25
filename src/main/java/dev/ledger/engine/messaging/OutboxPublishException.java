package dev.ledger.engine.messaging;

/**
 * Raised when an outbox event could not be shipped. The event stays unpublished and
 * is retried on the next poll — the outbox row is the source of truth, not the send.
 */
public class OutboxPublishException extends RuntimeException {

    public OutboxPublishException(long eventId, Throwable cause) {
        super("failed to publish outbox event id=" + eventId, cause);
    }
}
