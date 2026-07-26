package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledger.engine.domain.OutboxEvent;
import dev.ledger.engine.messaging.KafkaOutboxPublisher;
import dev.ledger.engine.messaging.LedgerTopics;
import dev.ledger.engine.messaging.OutboxPublishException;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Exercises the real publish path against an in-JVM broker.
 *
 * <p>Two things here cannot be established by mocking. First, that the payload is
 * written as raw JSON rather than a nested JSON string — a serializer mistake that
 * only appears on the wire. Second, that enabling observation on the template causes
 * a {@code traceparent} header to be attached, which is the mechanism the whole
 * cross-service trace depends on and whose failure mode is silence.
 */
class KafkaOutboxPublisherTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static EmbeddedKafkaBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, LedgerTopics.TRANSFERS_POSTED);
        broker.afterPropertiesSet();
    }

    @AfterAll
    static void stopBroker() {
        broker.destroy();
    }

    private static OutboxEvent event() {
        return new OutboxEvent(1L, 77L, "TRANSFER_POSTED",
                "{\"transactionId\":77,\"from\":1,\"to\":2,\"amountMinor\":500,\"currency\":\"USD\"}");
    }

    private KafkaTemplate<String, String> template(boolean observationEnabled) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.producerProps(broker));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);
        template.setObservationEnabled(observationEnabled);
        return template;
    }

    private Consumer<String, String> consumer(String group) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps(group, "true", broker));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer =
                new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, LedgerTopics.TRANSFERS_POSTED);
        return consumer;
    }

    /**
     * All tests publish to the same topic, so a prior test's record may still be on
     * it. Asserting against the most recent record keeps each test independent of
     * execution order.
     */
    private static ConsumerRecord<String, String> lastRecord(Consumer<String, String> consumer) {
        ConsumerRecord<String, String> last = null;
        for (ConsumerRecord<String, String> record :
                KafkaTestUtils.getRecords(consumer, TIMEOUT).records(LedgerTopics.TRANSFERS_POSTED)) {
            last = record;
        }
        assertThat(last).as("expected at least one published record").isNotNull();
        return last;
    }

    @Test
    @DisplayName("publishes the payload as raw JSON, keyed by transaction, with the event type")
    void publish_writesRecordOnTheWire() {
        try (Consumer<String, String> consumer = consumer("raw-json-group")) {
            new KafkaOutboxPublisher(template(false), TIMEOUT).publish(event());

            ConsumerRecord<String, String> record = lastRecord(consumer);

            assertThat(record.key()).isEqualTo("77");
            // Would start with a quote if the JSON had been serialized a second time.
            assertThat(record.value()).startsWith("{").contains("\"amountMinor\":500");
            assertThat(record.headers().lastHeader("event-type").value())
                    .isEqualTo("TRANSFER_POSTED".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Builds the same tracing stack the application runs: an OTel SDK tracer bridged
     * through Micrometer, with the W3C propagator. Using the real bridge matters — a
     * stub propagator would only prove the stub writes a header.
     */
    private ObservationRegistry tracingRegistry() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        io.opentelemetry.api.trace.Tracer otelTracer = sdk.getTracer("ledger-test");
        OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
        Tracer tracer = new OtelTracer(otelTracer, currentTraceContext, event -> { });
        Propagator propagator = new OtelPropagator(sdk.getPropagators(), otelTracer);

        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator));
        return registry;
    }

    @Test
    @DisplayName("attaches a traceparent header when observation is enabled")
    void publish_withObservation_propagatesTraceContext() {
        // KafkaTemplate resolves its ObservationRegistry from the application context
        // rather than a setter, so the registry is supplied the same way the running
        // app supplies it.
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(ObservationRegistry.class, this::tracingRegistry);
        context.refresh();

        KafkaTemplate<String, String> template = template(true);
        template.setApplicationContext(context);
        template.afterSingletonsInstantiated();

        try (Consumer<String, String> consumer = consumer("traceparent-group")) {
            new KafkaOutboxPublisher(template, TIMEOUT).publish(event());

            ConsumerRecord<String, String> record = lastRecord(consumer);

            // The header fraud-engine's consumer extracts. Absent it, both services
            // produce independent traces and nothing reports an error.
            assertThat(record.headers().lastHeader("traceparent"))
                    .as("traceparent must be written into the record for the trace to "
                            + "continue into fraud-engine")
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("fails loudly when the broker is unreachable so the event stays unpublished")
    void publish_unreachableBroker_throws() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        KafkaTemplate<String, String> unreachable =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, String>(props));

        assertThatThrownBy(() ->
                new KafkaOutboxPublisher(unreachable, Duration.ofSeconds(2)).publish(event()))
                .isInstanceOf(OutboxPublishException.class);
    }
}
