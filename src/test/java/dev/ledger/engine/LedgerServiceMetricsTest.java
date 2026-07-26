package dev.ledger.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.ledger.engine.domain.Account;
import dev.ledger.engine.domain.Entry;
import dev.ledger.engine.domain.EntryDirection;
import dev.ledger.engine.domain.Transaction;
import dev.ledger.engine.domain.TransactionStatus;
import dev.ledger.engine.domain.TransactionType;
import dev.ledger.engine.dto.BalanceResponse;
import dev.ledger.engine.dto.TransferRequest;
import dev.ledger.engine.observability.LedgerMetrics;
import dev.ledger.engine.repository.AccountRepository;
import dev.ledger.engine.repository.EntryRepository;
import dev.ledger.engine.repository.OutboxRepository;
import dev.ledger.engine.repository.TransactionRepository;
import dev.ledger.engine.service.LedgerService;
import dev.ledger.engine.service.PostResult;
import dev.ledger.engine.service.TransferProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The posted-transfer counter feeds dashboards and, indirectly, capacity decisions.
 * It must count value actually moving — not requests received — so the idempotent
 * replay path is the case that matters here.
 */
class LedgerServiceMetricsTest {

    private static final String KEY = "idem-1";

    private TransferProcessor processor;
    private TransactionRepository transactions;
    private EntryRepository entries;
    private AccountRepository accounts;
    private MeterRegistry registry;
    private LedgerService service;

    private static TransferRequest request() {
        return new TransferRequest(1L, 2L, 500L, "USD");
    }

    @BeforeEach
    void setUp() {
        processor = mock(TransferProcessor.class);
        transactions = mock(TransactionRepository.class);
        entries = mock(EntryRepository.class);
        accounts = mock(AccountRepository.class);
        registry = new SimpleMeterRegistry();
        service = new LedgerService(processor, transactions, entries, accounts,
                new LedgerMetrics(registry, mock(OutboxRepository.class)));
    }

    private double postedCount() {
        return registry.get("ledger.transfers.posted").counter().count();
    }

    @Test
    @DisplayName("counts a transfer that actually posts")
    void transfer_posted_isCounted() {
        when(transactions.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(processor.post(anyString(), anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(new PostResult(7L, List.of(new BalanceResponse(1L, 500L, "USD"))));

        service.transfer(KEY, request());

        assertThat(postedCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("does not count an idempotent replay — no value moved the second time")
    void transfer_idempotentReplay_isNotCounted() {
        Transaction existing = new Transaction(
                7L, KEY, TransactionType.TRANSFER, TransactionStatus.POSTED, null, null);
        when(transactions.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));
        when(entries.findByTransaction(7L)).thenReturn(
                List.of(new Entry(1L, 7L, 1L, -500L, EntryDirection.DEBIT, "USD", null)));
        when(entries.balanceOf(1L)).thenReturn(500L);
        when(accounts.findById(1L))
                .thenReturn(Optional.of(new Account(1L, "acct-1", "USD", false, null)));

        service.transfer(KEY, request());

        assertThat(postedCount()).isZero();
        verify(processor, never()).post(anyString(), anyLong(), anyLong(), anyLong(), anyString());
    }
}
