package dev.ledger.engine.repository;

import dev.ledger.engine.domain.Transaction;
import dev.ledger.engine.domain.TransactionStatus;
import dev.ledger.engine.domain.TransactionType;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    private static final RowMapper<Transaction> MAPPER = (rs, n) -> new Transaction(
            rs.getLong("id"),
            rs.getString("idempotency_key"),
            TransactionType.valueOf(rs.getString("type")),
            TransactionStatus.valueOf(rs.getString("status")),
            rs.getObject("reverses_tx_id", Long.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private static final String COLS = "id, idempotency_key, type, status, reverses_tx_id, created_at";

    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Transaction insert(String idempotencyKey, TransactionType type,
            TransactionStatus status, Long reversesTxId) {
        return jdbc.queryForObject(
                "INSERT INTO transactions (idempotency_key, type, status, reverses_tx_id) "
                        + "VALUES (?, ?, ?, ?) RETURNING " + COLS,
                MAPPER, idempotencyKey, type.name(), status.name(), reversesTxId);
    }

    public Optional<Transaction> findById(long id) {
        return jdbc.query("SELECT " + COLS + " FROM transactions WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Transaction> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT " + COLS + " FROM transactions WHERE idempotency_key = ?", MAPPER, key)
                .stream().findFirst();
    }

    public boolean reversalExistsFor(long originalTxId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE reverses_tx_id = ?", Integer.class, originalTxId);
        return count != null && count > 0;
    }
}
