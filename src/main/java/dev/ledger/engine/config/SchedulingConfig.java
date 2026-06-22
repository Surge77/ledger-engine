package dev.ledger.engine.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling (outbox poller + reconciliation job) is a production concern, gated
 * behind a flag so integration tests can switch the background threads off and
 * instead drive {@code publishBatch()} / {@code reconcile()} deterministically.
 * A scheduler racing a test's shared database or {@code @MockBean} is a
 * flaky-test source, not a behaviour under test. Enabled by default; the test
 * classpath sets {@code ledger.scheduling.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(name = "ledger.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
