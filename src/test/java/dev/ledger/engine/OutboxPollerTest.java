package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.ledger.engine.config.LedgerProperties;
import dev.ledger.engine.domain.OutboxEvent;
import dev.ledger.engine.messaging.OutboxPublishException;
import dev.ledger.engine.messaging.OutboxPublisher;
import dev.ledger.engine.repository.OutboxRepository;
import dev.ledger.engine.service.OutboxPoller;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Covers the at-least-once contract of the drain: an event may only be marked
 * published once the publisher confirms it. These are the cases where a naive
 * implementation silently loses committed ledger events.
 */
class OutboxPollerTest {

    private OutboxRepository outbox;
    private OutboxPublisher publisher;
    private OutboxPoller poller;

    private static OutboxEvent event(long id) {
        return new OutboxEvent(id, id * 10, "TRANSFER_POSTED", "{\"transactionId\":" + id + "}");
    }

    @BeforeEach
    void setUp() {
        outbox = mock(OutboxRepository.class);
        publisher = mock(OutboxPublisher.class);
        LedgerProperties properties = new LedgerProperties(
                "test-key",
                new LedgerProperties.Outbox(2000, 100, "log", 5000),
                new LedgerProperties.Reconciliation(60000));
        poller = new OutboxPoller(outbox, publisher, properties);
    }

    @Test
    @DisplayName("publishes every pending event and marks them all published")
    void publishBatch_allSucceed_marksAll() {
        when(outbox.fetchUnpublished(anyInt())).thenReturn(List.of(event(1), event(2), event(3)));

        int sent = poller.publishBatch();

        assertThat(sent).isEqualTo(3);
        verify(outbox).markPublished(List.of(1L, 2L, 3L));
    }

    @Test
    @DisplayName("marks nothing published when the very first send fails")
    void publishBatch_firstFails_marksNothing() {
        when(outbox.fetchUnpublished(anyInt())).thenReturn(List.of(event(1), event(2)));
        doThrow(new OutboxPublishException(1L, new RuntimeException("broker down")))
                .when(publisher).publish(event(1));

        int sent = poller.publishBatch();

        assertThat(sent).isZero();
        verify(outbox).markPublished(List.of());
    }

    @Test
    @DisplayName("stops at the first failure so later events are not delivered out of order")
    void publishBatch_middleFails_stopsAndMarksOnlyThePrefix() {
        when(outbox.fetchUnpublished(anyInt()))
                .thenReturn(List.of(event(1), event(2), event(3)));
        doThrow(new OutboxPublishException(2L, new RuntimeException("timeout")))
                .when(publisher).publish(event(2));

        int sent = poller.publishBatch();

        assertThat(sent).isEqualTo(1);
        verify(publisher, never()).publish(event(3));

        ArgumentCaptor<List<Long>> marked = ArgumentCaptor.forClass(List.class);
        verify(outbox).markPublished(marked.capture());
        assertThat(marked.getValue()).containsExactly(1L);
    }

    @Test
    @DisplayName("does not touch the publisher or the repository when nothing is pending")
    void publishBatch_nothingPending_isNoOp() {
        when(outbox.fetchUnpublished(anyInt())).thenReturn(List.of());

        assertThat(poller.publishBatch()).isZero();

        verify(outbox, never()).markPublished(anyList());
    }
}
