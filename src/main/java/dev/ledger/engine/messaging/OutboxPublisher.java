package dev.ledger.engine.messaging;

import dev.ledger.engine.domain.OutboxEvent;

/**
 * Ships a drained outbox event to its destination. Implementations must throw on
 * failure: the poller marks an event published only once this returns normally, so
 * a swallowed error would lose the event permanently.
 */
public interface OutboxPublisher {

    void publish(OutboxEvent event);
}
