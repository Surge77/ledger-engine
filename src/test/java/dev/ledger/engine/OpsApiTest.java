package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledger.engine.dto.ReconcileResult;
import dev.ledger.engine.dto.TransferRequest;
import dev.ledger.engine.dto.TransferResponse;
import dev.ledger.engine.repository.OutboxRepository;
import dev.ledger.engine.service.OutboxPoller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

// Raise scheduler intervals so the background poller/reconciler can't race the assertions.
@TestPropertySource(properties = {
        "ledger.outbox.poll-interval-ms=600000",
        "ledger.reconciliation.interval-ms=600000"
})
class OpsApiTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxPoller outboxPoller;

    @Autowired
    private OutboxRepository outboxRepository;

    private void transfer(long from, long to, long amount) {
        rest.postForEntity(url("/transfers"),
                new HttpEntity<>(new TransferRequest(from, to, amount, "INR"), jsonHeaders()),
                TransferResponse.class);
    }

    @Test
    void reconcileReportsZeroDriftAfterTransfers() {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 50_000, "INR");
        for (int i = 0; i < 10; i++) {
            transfer(alice, bob, 1_000);
        }

        ResponseEntity<ReconcileResult> response = rest.exchange(url("/admin/reconcile"),
                HttpMethod.GET, new HttpEntity<>(authHeaders()), ReconcileResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().invariantOk()).isTrue();
        assertThat(response.getBody().entriesSum()).isZero();
        assertThat(response.getBody().unbalancedTransactions()).isEmpty();
    }

    @Test
    void outboxPollerPublishesPendingEvents() {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 10_000, "INR");
        transfer(alice, bob, 2_000);

        assertThat(outboxRepository.unpublishedCount()).isEqualTo(1);

        int published = outboxPoller.publishBatch();

        assertThat(published).isEqualTo(1);
        assertThat(outboxRepository.unpublishedCount()).isZero();
    }
}
