package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledger.engine.dto.TransferRequest;
import dev.ledger.engine.exception.InsufficientFundsException;
import dev.ledger.engine.service.LedgerService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Proves NFR4: 100 concurrent transfers — no lost update, no negative balance, Σ stays 0. */
class ConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 100;
    private static final long AMOUNT = 1_000;

    @Autowired
    private LedgerService ledger;

    private RunResult runParallelTransfers(long from, long to, int threads) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    ledger.transfer(null, new TransferRequest(from, to, AMOUNT, "INR"));
                    succeeded.incrementAndGet();
                } catch (InsufficientFundsException expected) {
                    insufficient.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        return new RunResult(succeeded.get(), insufficient.get());
    }

    @Test
    void hundredConcurrentTransfersNoLostUpdate() throws InterruptedException {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, THREADS * AMOUNT, "INR"); // exactly enough for all

        RunResult result = runParallelTransfers(alice, bob, THREADS);

        assertThat(result.succeeded()).isEqualTo(THREADS);
        assertThat(balanceOf(alice)).isZero();
        assertThat(balanceOf(bob)).isEqualTo(THREADS * AMOUNT);
        assertThat(ledgerSum()).isZero();
    }

    @Test
    void oversubscribedTransfersNeverGoNegative() throws InterruptedException {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        long funded = (THREADS / 2) * AMOUNT; // only half can succeed
        deposit(alice, funded, "INR");

        RunResult result = runParallelTransfers(alice, bob, THREADS);

        assertThat(result.succeeded()).isEqualTo(THREADS / 2);
        assertThat(result.insufficient()).isEqualTo(THREADS / 2);
        assertThat(balanceOf(alice)).isZero();
        assertThat(balanceOf(alice)).isGreaterThanOrEqualTo(0);
        assertThat(balanceOf(bob)).isEqualTo(funded);
        assertThat(ledgerSum()).isZero();
    }

    private record RunResult(int succeeded, int insufficient) {
    }
}
