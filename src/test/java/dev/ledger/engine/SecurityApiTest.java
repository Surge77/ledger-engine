package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SecurityApiTest extends AbstractIntegrationTest {

    @Test
    void protectedEndpointWithoutKeyReturns401() {
        long alice = createAccount("Alice", "INR");

        ResponseEntity<String> response = rest.getForEntity(
                url("/accounts/" + alice + "/balance"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("UNAUTHORIZED");
    }

    @Test
    void protectedEndpointWithKeySucceeds() {
        long alice = createAccount("Alice", "INR");

        ResponseEntity<String> response = rest.exchange(url("/accounts/" + alice + "/balance"),
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void healthIsPublic() {
        ResponseEntity<String> response = rest.getForEntity(url("/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    void unknownPathReturns404() {
        ResponseEntity<String> response = rest.exchange(url("/does-not-exist"),
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
