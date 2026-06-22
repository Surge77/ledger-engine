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
            rs.getBoolean("is_system"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private static final String COLS = "id, name, currency, is_system, created_at";
    private static final String SYSTEM_NAME_PREFIX = "SYSTEM:";

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Account insert(String name, String currency) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, currency) VALUES (?, ?) RETURNING " + COLS,
                MAPPER, name, currency);
    }

    /** The per-currency boundary account money enters through; may run negative. */
    public Account insertSystem(String currency) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, currency, is_system) VALUES (?, ?, TRUE) RETURNING " + COLS,
                MAPPER, SYSTEM_NAME_PREFIX + currency, currency);
    }

    public Optional<Account> findById(long id) {
        return jdbc.query("SELECT " + COLS + " FROM accounts WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Account> findSystemAccount(String currency) {
        return jdbc.query("SELECT " + COLS + " FROM accounts WHERE is_system AND currency = ?",
                        MAPPER, currency)
                .stream().findFirst();
    }

    /** Acquires a row lock for the duration of the current transaction. */
    public Optional<Account> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLS + " FROM accounts WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }
}
