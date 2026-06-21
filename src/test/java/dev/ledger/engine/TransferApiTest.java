package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledger.engine.dto.TransferRequest;
import dev.ledger.engine.dto.TransferResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TransferApiTest extends AbstractIntegrationTest {

    private ResponseEntity<TransferResponse> post(TransferRequest req, String idempotencyKey) {
        HttpHeaders headers = jsonHeaders();
        if (idempotencyKey != null) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        return rest.postForEntity(url("/transfers"), new HttpEntity<>(req, headers), TransferResponse.class);
    }

    private ResponseEntity<String> postRaw(TransferRequest req) {
        return rest.postForEntity(url("/transfers"), new HttpEntity<>(req, jsonHeaders()), String.class);
    }

    private long transactionCount() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Long.class);
        return c == null ? 0 : c;
    }

    @Test
    void happyPathMovesBalancesAndWritesTwoEntries() {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 10_000, "INR");

        var response = post(new TransferRequest(alice, bob, 3_000L, "INR"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("POSTED");
        assertThat(balanceOf(alice)).isEqualTo(7_000);
        assertThat(balanceOf(bob)).isEqualTo(3_000);

        long txId = response.getBody().transferId();
        Long legs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM entries WHERE transaction_id = ?", Long.class, txId);
        assertThat(legs).isEqualTo(2);
    }

    @Test
    void ledgerSumIsAlwaysZero() {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 10_000, "INR");

        post(new TransferRequest(alice, bob, 4_200L, "INR"), null);

        assertThat(ledgerSum()).isZero();
    }

    @Test
    void replayingSameIdempotencyKeyAppliesOnce() {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 10_000, "INR");
        String key = "txn-key-123";

        var first = post(new TransferRequest(alice, bob, 3_000L, "INR"), key);
        var second = post(new TransferRequest(alice, bob, 3_000L, "INR"), key);

        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().transferId()).isEqualTo(first.getBody().transferId());
        assertThat(second.getBody().status()).isEqualTo("REPLAYED");
        assertThat(balanceOf(bob)).isEqualTo(3_000);

        Long keyed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?", Long.class, key);
        assertThat(keyed).isEqualTo(1);
    }

    @Test
    void insufficientFundsRejectedWithNoRowsWritten() {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 1_000, "INR");
        long before = transactionCount();

        var response = postRaw(new TransferRequest(alice, bob, 5_000L, "INR"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("INSUFFICIENT_FUNDS");
        assertThat(transactionCount()).isEqualTo(before);
        assertThat(balanceOf(alice)).isEqualTo(1_000);
        assertThat(balanceOf(bob)).isZero();
    }

    @Test
    void currencyMismatchRejectedWithNoRowsWritten() {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "USD");
        deposit(alice, 5_000, "INR");
        long before = transactionCount();

        var response = postRaw(new TransferRequest(alice, bob, 1_000L, "INR"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("CURRENCY_MISMATCH");
        assertThat(transactionCount()).isEqualTo(before);
    }

    @Test
    void nonexistentAccountRejected() {
        long alice = createAccount("Alice", "INR");
        deposit(alice, 5_000, "INR");

        var response = postRaw(new TransferRequest(alice, 999_999L, 1_000L, "INR"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("ACCOUNT_NOT_FOUND");
    }

    @Test
    void sameAccountTransferRejected() {
        long alice = createAccount("Alice", "INR");
        deposit(alice, 5_000, "INR");

        var response = postRaw(new TransferRequest(alice, alice, 1_000L, "INR"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("INVALID_TRANSFER");
    }
}
