package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledger.engine.dto.DepositRequest;
import dev.ledger.engine.dto.DepositResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class DepositApiTest extends AbstractIntegrationTest {

    private ResponseEntity<DepositResponse> deposit(long accountId, long amount, String currency, String key) {
        HttpHeaders headers = jsonHeaders();
        if (key != null) {
            headers.add("Idempotency-Key", key);
        }
        return rest.postForEntity(url("/accounts/" + accountId + "/deposit"),
                new HttpEntity<>(new DepositRequest(amount, currency), headers), DepositResponse.class);
    }

    private ResponseEntity<String> depositRaw(long accountId, long amount, String currency) {
        return rest.postForEntity(url("/accounts/" + accountId + "/deposit"),
                new HttpEntity<>(new DepositRequest(amount, currency), jsonHeaders()), String.class);
    }

    @Test
    void depositCreditsAccountAndKeepsLedgerBalanced() {
        long alice = createAccount("Alice", "INR");

        var response = deposit(alice, 50_000, "INR", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().balanceMinor()).isEqualTo(50_000);
        assertThat(balanceOf(alice)).isEqualTo(50_000);
        // System account holds the offsetting negative; system-wide sum stays zero.
        assertThat(ledgerSum()).isZero();
    }

    @Test
    void depositIsIdempotent() {
        long alice = createAccount("Alice", "INR");
        String key = "deposit-key-1";

        var first = deposit(alice, 50_000, "INR", key);
        var second = deposit(alice, 50_000, "INR", key);

        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().transactionId()).isEqualTo(first.getBody().transactionId());
        assertThat(second.getBody().status()).isEqualTo("REPLAYED");
        assertThat(balanceOf(alice)).isEqualTo(50_000);
    }

    @Test
    void depositCurrencyMismatchRejected() {
        long alice = createAccount("Alice", "INR");

        var response = depositRaw(alice, 1_000, "USD");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("CURRENCY_MISMATCH");
    }

    @Test
    void depositToUnknownAccountReturns404() {
        var response = depositRaw(999_999L, 1_000, "INR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("ACCOUNT_NOT_FOUND");
    }
}
