package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledger.engine.dto.Money;
import dev.ledger.engine.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class ErrorHandlingTest extends AbstractIntegrationTest {

    @Test
    void reverseNonexistentTransactionReturns404() {
        ResponseEntity<String> response = rest.exchange(url("/transfers/999999/reverse"),
                HttpMethod.POST, new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("TRANSACTION_NOT_FOUND");
    }

    @Test
    void malformedJsonBodyReturns400() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>("{ not valid json ", headers);

        ResponseEntity<String> response = rest.postForEntity(url("/transfers"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("MALFORMED_JSON");
    }

    @Test
    void getBalanceForUnknownAccountReturns404() {
        ResponseEntity<String> response = rest.exchange(url("/accounts/424242/balance"),
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("ACCOUNT_NOT_FOUND");
    }

    @Test
    void amountAboveMaximumReturns400() {
        var body = new TransferRequest(1L, 2L, Money.MAX_AMOUNT_MINOR + 1, "INR");

        ResponseEntity<String> response = rest.postForEntity(url("/transfers"),
                new HttpEntity<>(body, jsonHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_FAILED");
    }

    @Test
    void oversizedIdempotencyKeyReturns400() {
        HttpHeaders headers = jsonHeaders();
        headers.add("Idempotency-Key", "x".repeat(201));
        var body = new TransferRequest(1L, 2L, 1_000L, "INR");

        ResponseEntity<String> response = rest.postForEntity(url("/transfers"),
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_FAILED");
    }

    @Test
    void deepPaginationReturnsEmptyPageNotServerError() {
        long alice = createAccount("Alice", "INR");

        // A page index that would overflow int*int offset arithmetic if not widened to long.
        ResponseEntity<String> response = rest.exchange(
                url("/accounts/" + alice + "/entries?page=2000000000&size=200"),
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"items\":[]");
    }
}
