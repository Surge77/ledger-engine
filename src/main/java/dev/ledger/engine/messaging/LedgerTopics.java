package dev.ledger.engine.messaging;

/**
 * Kafka topics this service owns. Ledger publishes its own domain events here —
 * consumers translate into their own models rather than the ledger conforming to
 * theirs.
 */
public final class LedgerTopics {

    public static final String TRANSFERS_POSTED = "ledger.transfers.posted";

    private LedgerTopics() {
    }
}
