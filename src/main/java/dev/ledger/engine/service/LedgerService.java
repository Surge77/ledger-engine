package dev.ledger.engine.service;

import dev.ledger.engine.domain.Account;
import dev.ledger.engine.domain.Entry;
import dev.ledger.engine.domain.Transaction;
import dev.ledger.engine.dto.BalanceResponse;
import dev.ledger.engine.dto.DepositRequest;
import dev.ledger.engine.dto.DepositResponse;
import dev.ledger.engine.dto.EntryResponse;
import dev.ledger.engine.dto.PageResponse;
import dev.ledger.engine.dto.ReversalResponse;
import dev.ledger.engine.dto.TransferRequest;
import dev.ledger.engine.dto.TransferResponse;
import dev.ledger.engine.exception.AccountNotFoundException;
import dev.ledger.engine.exception.CurrencyMismatchException;
import dev.ledger.engine.exception.InvalidTransferException;
import dev.ledger.engine.repository.AccountRepository;
import dev.ledger.engine.repository.EntryRepository;
import dev.ledger.engine.repository.TransactionRepository;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Orchestration around the transactional {@link TransferProcessor}: idempotency
 * replay, the concurrent duplicate-key fallback, and the read endpoints. Kept
 * out of the SERIALIZABLE transaction so a replay never re-locks accounts.
 */
@Service
public class LedgerService {

    private static final int MAX_RETRIES = 5;
    private static final long RETRY_BASE_BACKOFF_MS = 5;

    private final TransferProcessor processor;
    private final TransactionRepository transactions;
    private final EntryRepository entries;
    private final AccountRepository accounts;

    public LedgerService(TransferProcessor processor, TransactionRepository transactions,
            EntryRepository entries, AccountRepository accounts) {
        this.processor = processor;
        this.transactions = transactions;
        this.entries = entries;
        this.accounts = accounts;
    }

    public TransferResponse transfer(String idempotencyKey, TransferRequest req) {
        boolean keyed = StringUtils.hasText(idempotencyKey);
        if (keyed) {
            var existing = transactions.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return replay(existing.get());
            }
        }
        // SERIALIZABLE can abort with a transient serialization/deadlock error under
        // contention; the correct response is to retry the whole transaction.
        int attempt = 0;
        while (true) {
            try {
                PostResult result = processor.post(
                        idempotencyKey, req.from(), req.to(), req.amountMinor(), req.currency());
                return new TransferResponse(result.transactionId(), "POSTED", result.balances());
            } catch (DuplicateKeyException raceLost) {
                return transactions.findByIdempotencyKey(idempotencyKey)
                        .map(this::replay)
                        .orElseThrow(() -> raceLost);
            } catch (TransientDataAccessException transientEx) {
                if (++attempt >= MAX_RETRIES) {
                    throw transientEx;
                }
                backoff(attempt);
            }
        }
    }

    public DepositResponse deposit(String idempotencyKey, long accountId, DepositRequest req) {
        Account target = requireAccount(accountId);
        if (!target.currency().equals(req.currency())) {
            throw new CurrencyMismatchException("deposit currency " + req.currency()
                    + " must match account currency " + target.currency());
        }
        long systemId = systemAccountId(req.currency());
        TransferResponse posted = transfer(idempotencyKey,
                new TransferRequest(systemId, accountId, req.amountMinor(), req.currency()));
        long balance = posted.balances().stream()
                .filter(b -> b.account() == accountId)
                .map(BalanceResponse::balanceMinor)
                .findFirst()
                .orElseGet(() -> entries.balanceOf(accountId));
        return new DepositResponse(posted.transferId(), accountId, balance, req.currency(), posted.status());
    }

    /** Resolve the per-currency system account, creating it on first use. */
    private long systemAccountId(String currency) {
        return accounts.findSystemAccount(currency)
                .map(Account::id)
                .orElseGet(() -> {
                    try {
                        return accounts.insertSystem(currency).id();
                    } catch (DuplicateKeyException raceLost) {
                        return accounts.findSystemAccount(currency).orElseThrow().id();
                    }
                });
    }

    private void backoff(int attempt) {
        try {
            // Linear backoff with jitter so contending threads don't retry in lockstep.
            long jitter = ThreadLocalRandom.current().nextLong(RETRY_BASE_BACKOFF_MS);
            Thread.sleep(RETRY_BASE_BACKOFF_MS * attempt + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during transfer retry backoff", e);
        }
    }

    public ReversalResponse reverse(long originalTransferId) {
        int attempt = 0;
        while (true) {
            try {
                PostResult result = processor.reverse(originalTransferId);
                return new ReversalResponse(result.transactionId(), originalTransferId, "POSTED");
            } catch (DuplicateKeyException raceLost) {
                // Lost the race for the unique reversal: another reversal already exists.
                throw new InvalidTransferException(
                        "transaction " + originalTransferId + " is already reversed");
            } catch (TransientDataAccessException transientEx) {
                if (++attempt >= MAX_RETRIES) {
                    throw transientEx;
                }
                backoff(attempt);
            }
        }
    }

    @Transactional(readOnly = true)
    public BalanceResponse balance(long accountId) {
        Account account = requireAccount(accountId);
        return new BalanceResponse(accountId, entries.balanceOf(accountId), account.currency());
    }

    @Transactional(readOnly = true)
    public PageResponse<EntryResponse> entries(long accountId, int page, int size) {
        requireAccount(accountId);
        List<Entry> rows = entries.findByAccount(accountId, size + 1, (long) page * size);
        boolean hasMore = rows.size() > size;
        List<EntryResponse> items = rows.stream().limit(size).map(EntryResponse::from).toList();
        return new PageResponse<>(items, page, size, hasMore);
    }

    private TransferResponse replay(Transaction tx) {
        List<BalanceResponse> balances = entries.findByTransaction(tx.id()).stream()
                .map(Entry::accountId).distinct().sorted()
                .map(id -> new BalanceResponse(id, entries.balanceOf(id), requireAccount(id).currency()))
                .toList();
        return new TransferResponse(tx.id(), "REPLAYED", balances);
    }

    private Account requireAccount(long accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
