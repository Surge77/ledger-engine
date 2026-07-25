package dev.ledger.engine.config;

import dev.ledger.engine.messaging.KafkaOutboxPublisher;
import dev.ledger.engine.messaging.LoggingOutboxPublisher;
import dev.ledger.engine.messaging.OutboxPublisher;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Selects how drained outbox events are shipped. Defaults to logging so the service
 * boots without a broker — Kafka is opt-in via {@code ledger.outbox.publisher=kafka}.
 */
@Configuration
public class OutboxPublisherConfig {

    @Bean
    @ConditionalOnProperty(name = "ledger.outbox.publisher", havingValue = "log", matchIfMissing = true)
    public OutboxPublisher loggingOutboxPublisher() {
        return new LoggingOutboxPublisher();
    }

    @Bean
    @ConditionalOnProperty(name = "ledger.outbox.publisher", havingValue = "kafka")
    public OutboxPublisher kafkaOutboxPublisher(
            KafkaTemplate<String, String> kafkaTemplate, LedgerProperties properties) {
        return new KafkaOutboxPublisher(
                kafkaTemplate, Duration.ofMillis(properties.outbox().sendTimeoutMs()));
    }
}
