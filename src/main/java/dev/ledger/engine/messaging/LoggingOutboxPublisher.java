package dev.ledger.engine.messaging;

import dev.ledger.engine.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default publisher: records the drain without requiring a broker, preserving the
 * behavior the service had before Kafka existed. Keeps local dev and the test suite
 * runnable with no infrastructure.
 */
public class LoggingOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        // Payload carries account ids + amounts — keep it off INFO-level logs.
        log.info("outbox publish id={} type={}", event.id(), event.eventType());
        log.debug("outbox payload id={} {}", event.id(), event.payload());
    }
}
