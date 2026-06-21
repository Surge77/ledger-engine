package dev.ledger.engine.repository;

import dev.ledger.engine.domain.Account;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final RowMapper<Account> MAPPER = (rs, n) -> new Account(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("currency"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Account insert(String name, String currency) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, currency) VALUES (?, ?) "
                        + "RETURNING id, name, currency, created_at",
                MAPPER, name, currency);
    }

    public Optional<Account> findById(long id) {
        return jdbc.query("SELECT id, name, currency, created_at FROM accounts WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** Acquires a row lock for the duration of the current transaction. */
    public Optional<Account> findByIdForUpdate(long id) {
        return jdbc.query(
                        "SELECT id, name, currency, created_at FROM accounts WHERE id = ? FOR UPDATE",
                        MAPPER, id)
                .stream().findFirst();
    }
}
