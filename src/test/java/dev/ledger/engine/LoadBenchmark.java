package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledger.engine.dto.TransferRequest;
import dev.ledger.engine.dto.TransferResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;

/**
 * On-demand throughput/latency benchmark over the full HTTP stack. Not part of the
 * normal suite (no *Test suffix). Run explicitly:
 *   mvn -Dtest=LoadBenchmark test
 *
 * Two scenarios: disjoint account pairs (true parallel throughput) and a single
 * hot pair (worst-case lock contention — every transfer serializes on one row).
 */
class LoadBenchmark extends AbstractIntegrationTest {

    private static final int THREADS = 32;
    private static final int PER_THREAD = 250; // 8,000 measured transfers total

    @Test
    void disjointPairsThroughput() throws InterruptedException {
        long[] senders = new long[THREADS];
        long[] receivers = new long[THREADS];
        for (int i = 0; i < THREADS; i++) {
            senders[i] = createAccount("sender-" + i, "INR");
            receivers[i] = createAccount("receiver-" + i, "INR");
            deposit(senders[i], PER_THREAD * 4L, "INR");
        }
        report("disjoint pairs (no lock contention)",
                run(senders, receivers, true));
    }

    @Test
    void singleHotPairContention() throws InterruptedException {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, THREADS * PER_THREAD * 4L, "INR");
        long[] senders = new long[THREADS];
        long[] receivers = new long[THREADS];
        java.util.Arrays.fill(senders, alice);
        java.util.Arrays.fill(receivers, bob);
        report("single hot pair (full serialization on one row)",
                run(senders, receivers, false));
    }

    private long[] run(long[] senders, long[] receivers, boolean warm) throws InterruptedException {
        if (warm) {
            warmup(senders[0], receivers[0]);
        }
        long[] latenciesNs = new long[THREADS * PER_THREAD];
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Long> walls = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            final int threadIdx = t;
            pool.submit(() -> {
                var request = new HttpEntity<>(
                        new TransferRequest(senders[threadIdx], receivers[threadIdx], 1L, "INR"),
                        jsonHeaders());
                ready.countDown();
                await(go);
                int base = threadIdx * PER_THREAD;
                for (int i = 0; i < PER_THREAD; i++) {
                    long t0 = System.nanoTime();
                    rest.postForEntity(url("/transfers"), request, TransferResponse.class);
                    latenciesNs[base + i] = System.nanoTime() - t0;
                }
            });
        }
        ready.await();
        long startNs = System.nanoTime();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.MINUTES)).isTrue();
        walls.add(System.nanoTime() - startNs);
        assertThat(ledgerSum()).isZero();
        long[] result = new long[latenciesNs.length + 1];
        System.arraycopy(latenciesNs, 0, result, 0, latenciesNs.length);
        result[latenciesNs.length] = walls.get(0); // smuggle wall time in last slot
        return result;
    }

    private void warmup(long from, long to) {
        var request = new HttpEntity<>(new TransferRequest(from, to, 1L, "INR"), jsonHeaders());
        for (int i = 0; i < 500; i++) {
            rest.postForEntity(url("/transfers"), request, TransferResponse.class);
        }
    }

    private void report(String label, long[] data) {
        int n = data.length - 1;
        long wallNs = data[n];
        long[] lat = java.util.Arrays.copyOf(data, n);
        java.util.Arrays.sort(lat);
        double seconds = wallNs / 1_000_000_000.0;
        System.out.printf("%n=== %s | %d threads ===%n", label, THREADS);
        System.out.printf("transfers:   %d in %.2fs%n", n, seconds);
        System.out.printf("throughput:  %.0f transfers/sec%n", n / seconds);
        System.out.printf("p50:         %.2f ms%n", ms(lat, 50));
        System.out.printf("p95:         %.2f ms%n", ms(lat, 95));
        System.out.printf("p99:         %.2f ms%n", ms(lat, 99));
        System.out.println("========================================");
    }

    private double ms(long[] sortedNs, int percentile) {
        int idx = (int) Math.ceil(percentile / 100.0 * sortedNs.length) - 1;
        return sortedNs[Math.max(0, idx)] / 1_000_000.0;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
