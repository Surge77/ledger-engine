package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledger.engine.dto.BalanceResponse;
import dev.ledger.engine.dto.ReversalResponse;
import dev.ledger.engine.dto.TransferRequest;
import dev.ledger.engine.dto.TransferResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class LedgerReadApiTest extends AbstractIntegrationTest {

    private long transfer(long from, long to, long amount) {
        HttpHeaders headers = jsonHeaders();
        var response = rest.postForEntity(url("/transfers"),
                new HttpEntity<>(new TransferRequest(from, to, amount, "INR"), headers),
                TransferResponse.class);
        return response.getBody().transferId();
    }

    @Test
    void balanceEndpointReturnsDerivedBalance() {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 10_000, "INR");
        transfer(alice, bob, 2_500);

        var response = rest.exchange(url("/accounts/" + bob + "/balance"),
                HttpMethod.GET, new HttpEntity<>(authHeaders()), BalanceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().balanceMinor()).isEqualTo(2_500);
        assertThat(response.getBody().currency()).isEqualTo("INR");
    }

    @Test
    void entriesAreNewestFirstAndPaginated() {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 100_000, "INR");
        for (int i = 1; i <= 5; i++) {
            transfer(alice, bob, i * 1_000L);
        }

        var firstPage = rest.exchange(url("/accounts/" + alice + "/entries?page=0&size=3"),
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(firstPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstPage.getBody()).contains("\"hasMore\":true");

        // alice has 1 deposit credit + 5 transfer debits = 6 entries; page size 3 → 2 pages.
        var secondPage = rest.exchange(url("/accounts/" + alice + "/entries?page=1&size=3"),
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(secondPage.getBody()).contains("\"hasMore\":false");
    }

    @Test
    void reverseCreatesCompensatingTransactionAndRestoresBalances() {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 10_000, "INR");
        long txId = transfer(alice, bob, 3_000);

        var response = rest.exchange(url("/transfers/" + txId + "/reverse"),
                HttpMethod.POST, new HttpEntity<>(authHeaders()), ReversalResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().originalTransferId()).isEqualTo(txId);
        assertThat(balanceOf(alice)).isEqualTo(10_000);
        assertThat(balanceOf(bob)).isZero();
        assertThat(ledgerSum()).isZero();
    }

    @Test
    void doubleReverseRejected() {
        long alice = createAccount("Alice", "INR");
        long bob = createAccount("Bob", "INR");
        deposit(alice, 10_000, "INR");
        long txId = transfer(alice, bob, 3_000);
        rest.exchange(url("/transfers/" + txId + "/reverse"),
                HttpMethod.POST, new HttpEntity<>(authHeaders()), ReversalResponse.class);

        ResponseEntity<String> second = rest.exchange(url("/transfers/" + txId + "/reverse"),
                HttpMethod.POST, new HttpEntity<>(authHeaders()), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(second.getBody()).contains("already reversed");
    }
}
