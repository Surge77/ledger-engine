package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import dev.ledger.engine.dto.TransferRequest;
import dev.ledger.engine.repository.OutboxRepository;
import dev.ledger.engine.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Proves NFR2/NFR5: a failure after the entries are written (here the outbox
 * insert — the last write before commit, standing in for a crash) rolls back the
 * whole transaction. No half-transfer survives.
 */
class AtomicityTest extends AbstractIntegrationTest {

    @MockBean
    private OutboxRepository outbox;

    @Autowired
    private LedgerService ledger;

    private long count(String table) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return c == null ? 0 : c;
    }

    @Test
    void failedOutboxWriteRollsBackEntireTransfer() {
        doThrow(new RuntimeException("simulated crash before commit"))
                .when(outbox).insert(anyLong(), any(), any());
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 10_000, "INR");
        long txBefore = count("transactions");
        long entriesBefore = count("entries");

        assertThatThrownBy(() -> ledger.transfer(null, new TransferRequest(alice, bob, 3_000L, "INR")))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("transactions")).isEqualTo(txBefore);
        assertThat(count("entries")).isEqualTo(entriesBefore);
        assertThat(balanceOf(alice)).isEqualTo(10_000);
        assertThat(balanceOf(bob)).isZero();
        assertThat(ledgerSum()).isZero();
    }
}
