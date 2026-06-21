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

    protected HttpHeaders jsonHeaders() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Creates an account directly in the DB and returns its id. */
    protected long createAccount(String name, String currency) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, currency) VALUES (?, ?) RETURNING id",
                Long.class, name, currency);
    }

    /**
     * Seeds funds by posting a balanced funding transaction straight to the DB:
     * an external account is debited, the target credited. Bypasses the engine's
     * no-overdraft rule so tests can set up funded accounts.
     */
    protected void deposit(long accountId, long amountMinor, String currency) {
        long external = createAccount("external-funding", currency);
        Long txId = jdbc.queryForObject(
                "INSERT INTO transactions (type, status) VALUES ('TRANSFER', 'POSTED') RETURNING id",
                Long.class);
        // Single statement so the deferred balance trigger sees both legs at commit.
        jdbc.update("INSERT INTO entries (transaction_id, account_id, amount_minor, direction, currency) "
                        + "VALUES (?, ?, ?, 'DEBIT', ?), (?, ?, ?, 'CREDIT', ?)",
                txId, external, -amountMinor, currency,
                txId, accountId, amountMinor, currency);
    }

    protected long balanceOf(long accountId) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM entries WHERE account_id = ?",
                Long.class, accountId);
        return sum == null ? 0L : sum;
    }

    protected long ledgerSum() {
        Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM entries", Long.class);
        return sum == null ? 0L : sum;
    }
}
