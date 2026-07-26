package dev.ledger.engine.messaging;

import dev.ledger.engine.domain.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes drained outbox events to Kafka.
 *
 * <p>The send is awaited rather than fired asynchronously: the poller marks an event
 * published only after this returns, so returning before the broker acknowledges
 * would risk marking an event that never landed. Waiting trades a little throughput
 * for the at-least-once guarantee the outbox exists to provide.
 *
 * <p>The payload column is already JSON, so it is sent as a raw string — serializing
 * it again would nest it inside a JSON string literal.
 */
public class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final Duration sendTimeout;

    public KafkaOutboxPublisher(KafkaTemplate<String, String> kafka, Duration sendTimeout) {
        this.kafka = kafka;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publish(OutboxEvent event) {
        // Keyed by transaction so all events for one transaction share a partition
        // and therefore keep their relative order.
        String key = Long.toString(event.transactionId());
        ProducerRecord<String, String> record =
                new ProducerRecord<>(LedgerTopics.TRANSFERS_POSTED, key, event.payload());
        record.headers().add("event-type", event.eventType().getBytes(StandardCharsets.UTF_8));

        try {
            kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutboxPublishException(event.id(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new OutboxPublishException(event.id(), e);
        } catch (RuntimeException e) {
            // send() can fail synchronously — an unreachable broker surfaces as
            // KafkaException from send() itself, never reaching the future. Without
            // this branch that escapes untranslated and the event id is lost from the
            // failure, breaking the contract this interface documents.
            throw new OutboxPublishException(event.id(), e);
        }
    }
}
