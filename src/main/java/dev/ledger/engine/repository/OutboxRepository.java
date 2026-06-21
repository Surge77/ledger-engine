package dev.ledger.engine.repository;

import dev.ledger.engine.domain.OutboxEvent;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private static final RowMapper<OutboxEvent> MAPPER = (rs, n) -> new OutboxEvent(
            rs.getLong("id"),
            rs.getLong("transaction_id"),
            rs.getString("event_type"),
            rs.getString("payload"));

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Written in the same transaction as the ledger entries — atomic with the post. */
    public void insert(long transactionId, String eventType, String payloadJson) {
        jdbc.update(
                "INSERT INTO outbox (transaction_id, event_type, payload) VALUES (?, ?, ?::jsonb)",
                transactionId, eventType, payloadJson);
    }

    public List<OutboxEvent> fetchUnpublished(int limit) {
        return jdbc.query(
                "SELECT id, transaction_id, event_type, payload FROM outbox "
                        + "WHERE published_at IS NULL ORDER BY id LIMIT ?",
                MAPPER, limit);
    }

    public void markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "UPDATE outbox SET published_at = now() WHERE id = ?",
                ids, ids.size(), (ps, id) -> ps.setLong(1, id));
    }

    public long unpublishedCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL", Long.class);
        return count == null ? 0L : count;
    }
}
