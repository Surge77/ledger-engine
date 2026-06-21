package dev.ledger.engine.service;

import dev.ledger.engine.config.LedgerProperties;
import dev.ledger.engine.domain.OutboxEvent;
import dev.ledger.engine.repository.OutboxRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the transactional outbox: events are written in the same DB transaction
 * as the ledger post, so this poller ships exactly what was committed — no event
 * without a transaction, no transaction without an event. Kafka-ready (logs for now).
 */
@Service
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository outbox;
    private final int batchSize;

    public OutboxPoller(OutboxRepository outbox, LedgerProperties properties) {
        this.outbox = outbox;
        this.batchSize = properties.outbox().batchSize();
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval-ms}")
    @Transactional
    public int publishBatch() {
        List<OutboxEvent> pending = outbox.fetchUnpublished(batchSize);
        if (pending.isEmpty()) {
            return 0;
        }
        for (OutboxEvent event : pending) {
            log.info("outbox publish id={} type={} payload={}",
                    event.id(), event.eventType(), event.payload());
        }
        outbox.markPublished(pending.stream().map(OutboxEvent::id).toList());
        return pending.size();
    }
}
