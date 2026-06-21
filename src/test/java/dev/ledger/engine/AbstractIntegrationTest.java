package dev.ledger.engine;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Boots the full app against the real local Postgres (H2 won't honor SERIALIZABLE /
 * FOR UPDATE, which the ledger depends on). Each test starts from clean tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @LocalServerPort
    protected int port;

    @org.springframework.beans.factory.annotation.Value("${ledger.api-key}")
    protected String apiKey;

    @BeforeEach
    void resetSchema() {
        jdbc.execute("TRUNCATE outbox, entries, transactions, accounts RESTART IDENTITY CASCADE");
    }

    protected String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    protected HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Api-Key", apiKey);
        return headers;
    }
}
