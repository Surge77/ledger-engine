package dev.ledger.engine.observability;

import dev.ledger.engine.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business-level meters. HTTP and JVM metrics come free from Actuator; these are the
 * ledger-specific signals an SLO can actually be written against.
 *
 * <p>Outbox lag is a gauge rather than a counter because it answers "how far behind
 * are we right now" — the question that matters when the broker is degraded. It is
 * the earliest indicator that transfers are committing but not reaching fraud
 * scoring, which no HTTP metric would reveal.
 */
@Component
public class LedgerMetrics {

    private final Counter transfersPosted;
    private final Counter outboxPublished;
    private final Counter outboxPublishFailures;

    public LedgerMetrics(MeterRegistry registry, OutboxRepository outbox) {
        this.transfersPosted = Counter.builder("ledger.transfers.posted")
                .description("Transfers successfully posted to the ledger")
                .register(registry);
        this.outboxPublished = Counter.builder("ledger.outbox.published")
                .description("Outbox events confirmed by the publisher")
                .register(registry);
        this.outboxPublishFailures = Counter.builder("ledger.outbox.publish.failures")
                .description("Outbox publish attempts that failed and will be retried")
                .register(registry);

        registry.gauge("ledger.outbox.lag", outbox, repo -> (double) repo.unpublishedCount());
    }

    public void transferPosted() {
        transfersPosted.increment();
    }

    public void outboxPublished(int count) {
        outboxPublished.increment(count);
    }

    public void outboxPublishFailed() {
        outboxPublishFailures.increment();
    }
}
