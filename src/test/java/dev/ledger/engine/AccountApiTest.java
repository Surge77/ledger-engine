package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledger.engine.dto.AccountResponse;
import dev.ledger.engine.dto.CreateAccountRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class AccountApiTest extends AbstractIntegrationTest {

    @Test
    void createsAndReadsBackAccount() {
        var headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(new CreateAccountRequest("Alice", "INR"), headers);

        ResponseEntity<AccountResponse> created =
                rest.postForEntity(url("/accounts"), request, AccountResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().id()).isPositive();
        assertThat(created.getBody().name()).isEqualTo("Alice");
        assertThat(created.getBody().currency()).isEqualTo("INR");

        long id = created.getBody().id();
        ResponseEntity<AccountResponse> fetched = rest.exchange(
                url("/accounts/" + id), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authHeaders()), AccountResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().id()).isEqualTo(id);
    }

    @Test
    void rejectsInvalidCurrencyWith400() {
        var headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(new CreateAccountRequest("Bob", "rupees"), headers);

        ResponseEntity<String> response = rest.postForEntity(url("/accounts"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_FAILED");
    }
}
