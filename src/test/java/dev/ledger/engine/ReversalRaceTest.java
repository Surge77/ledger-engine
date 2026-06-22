package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledger.engine.dto.TransferRequest;
import dev.ledger.engine.exception.InvalidTransferException;
import dev.ledger.engine.service.LedgerService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the double-reversal race is closed: many threads reversing the same
 * transaction produce exactly one compensating transaction, the rest are
 * rejected, and the original is reversed exactly once.
 */
class ReversalRaceTest extends AbstractIntegrationTest {

    private static final int THREADS = 50;

    @Autowired
    private LedgerService ledger;

    @Test
    void concurrentReversesApplyExactlyOnce() throws InterruptedException {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 10_000, "INR");
        long txId = ledger.transfer(null, new TransferRequest(alice, bob, 3_000L, "INR")).transferId();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger reversed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    ledger.reverse(txId);
                    reversed.incrementAndGet();
                } catch (InvalidTransferException alreadyReversed) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(reversed.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(THREADS - 1);
        Long reversals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE reverses_tx_id = ?", Long.class, txId);
        assertThat(reversals).isEqualTo(1);
        assertThat(balanceOf(alice)).isEqualTo(10_000);
        assertThat(balanceOf(bob)).isZero();
        assertThat(ledgerSum()).isZero();
    }
}
