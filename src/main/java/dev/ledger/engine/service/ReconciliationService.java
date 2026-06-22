package dev.ledger.engine.service;

import dev.ledger.engine.dto.ReconcileResult;
import dev.ledger.engine.repository.EntryRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the double-entry invariant: global SUM(amount_minor) = 0 and every
 * transaction nets to zero. The DB trigger prevents drift; this is the audit
 * that demonstrates there is none.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int MAX_DRIFT_REPORTED = 1000;

    private final JdbcTemplate jdbc;
    private final EntryRepository entries;

    public ReconciliationService(JdbcTemplate jdbc, EntryRepository entries) {
        this.jdbc = jdbc;
        this.entries = entries;
    }

    @Transactional(readOnly = true)
    public ReconcileResult reconcile() {
        long entriesSum = entries.totalSum();
        // Bounded so a drift incident on a large table can't return an unbounded set.
        List<Long> unbalanced = jdbc.queryForList(
                "SELECT transaction_id FROM entries GROUP BY transaction_id "
                        + "HAVING SUM(amount_minor) <> 0 ORDER BY transaction_id LIMIT ?",
                Long.class, MAX_DRIFT_REPORTED + 1);
        boolean truncated = unbalanced.size() > MAX_DRIFT_REPORTED;
        if (truncated) {
            unbalanced = unbalanced.subList(0, MAX_DRIFT_REPORTED);
        }
        boolean ok = entriesSum == 0 && unbalanced.isEmpty();
        return new ReconcileResult(ok, entriesSum, unbalanced, truncated, Instant.now());
    }

    @Scheduled(fixedDelayString = "${ledger.reconciliation.interval-ms}")
    public void scheduledReconcile() {
        ReconcileResult result = reconcile();
        if (result.invariantOk()) {
            log.info("reconciliation ok: entriesSum=0, 0 drift");
        } else {
            log.error("reconciliation DRIFT: entriesSum={}, unbalancedTransactions={}",
                    result.entriesSum(), result.unbalancedTransactions());
        }
    }
}
