package dev.ledger.engine.repository;

import dev.ledger.engine.domain.Entry;
import dev.ledger.engine.domain.EntryDirection;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EntryRepository {

    private static final RowMapper<Entry> MAPPER = (rs, n) -> new Entry(
            rs.getLong("id"),
            rs.getLong("transaction_id"),
            rs.getLong("account_id"),
            rs.getLong("amount_minor"),
            EntryDirection.valueOf(rs.getString("direction")),
            rs.getString("currency"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private static final String COLS =
            "id, transaction_id, account_id, amount_minor, direction, currency, created_at";

    private final JdbcTemplate jdbc;

    public EntryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long transactionId, long accountId, long amountMinor,
            EntryDirection direction, String currency) {
        jdbc.update(
                "INSERT INTO entries (transaction_id, account_id, amount_minor, direction, currency) "
                        + "VALUES (?, ?, ?, ?, ?)",
                transactionId, accountId, amountMinor, direction.name(), currency);
    }

    /** Derived balance: the whole point — never a stored mutable column. */
    public long balanceOf(long accountId) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM entries WHERE account_id = ?",
                Long.class, accountId);
        return sum == null ? 0L : sum;
    }

    public List<Entry> findByAccount(long accountId, int limit, long offset) {
        return jdbc.query(
                "SELECT " + COLS + " FROM entries WHERE account_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                MAPPER, accountId, limit, offset);
    }

    public List<Entry> findByTransaction(long transactionId) {
        return jdbc.query(
                "SELECT " + COLS + " FROM entries WHERE transaction_id = ? ORDER BY id", MAPPER, transactionId);
    }

    public long totalSum() {
        Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM entries", Long.class);
        return sum == null ? 0L : sum;
    }
}
