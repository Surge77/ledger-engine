package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;

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
}
