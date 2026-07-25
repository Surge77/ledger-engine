package dev.ledger.engine.service;

import dev.ledger.engine.config.LedgerProperties;
import dev.ledger.engine.domain.OutboxEvent;
import dev.ledger.engine.messaging.OutboxPublisher;
import dev.ledger.engine.observability.LedgerMetrics;
import dev.ledger.engine.repository.OutboxRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the transactional outbox: events are written in the same DB transaction
 * as the ledger post, so this poller ships exactly what was committed — no event
 * without a transaction, no transaction without an event.
 *
 * <p>Delivery is at-least-once by design. An event is marked published only after
 * the publisher confirms it, so a crash between send and mark redelivers rather
 * than drops. Consumers must therefore be idempotent.
 */
@Service
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository outbox;
    private final OutboxPublisher publisher;
    private final LedgerMetrics metrics;
    private final int batchSize;

    public OutboxPoller(OutboxRepository outbox, OutboxPublisher publisher,
                        LedgerMetrics metrics, LedgerProperties properties) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.metrics = metrics;
        this.batchSize = properties.outbox().batchSize();
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval-ms}")
    @Transactional
    public int publishBatch() {
        List<OutboxEvent> pending = outbox.fetchUnpublished(batchSize);
        if (pending.isEmpty()) {
            return 0;
        }

        // Stop at the first failure rather than skipping past it: events are ordered
        // by id, and publishing a later event after an earlier one failed would
        // deliver a transaction's history out of order.
        List<Long> sent = new ArrayList<>();
        for (OutboxEvent event : pending) {
            try {
                publisher.publish(event);
                sent.add(event.id());
            } catch (RuntimeException e) {
                metrics.outboxPublishFailed();
                log.warn("outbox publish failed id={} — {} of {} sent, rest retried next poll",
                        event.id(), sent.size(), pending.size(), e);
                break;
            }
        }

        outbox.markPublished(sent);
        metrics.outboxPublished(sent.size());
        return sent.size();
    }
}
