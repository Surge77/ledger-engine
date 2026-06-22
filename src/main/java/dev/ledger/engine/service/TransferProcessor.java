package dev.ledger.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.engine.domain.Account;
import dev.ledger.engine.domain.Entry;
import dev.ledger.engine.domain.EntryDirection;
import dev.ledger.engine.domain.Transaction;
import dev.ledger.engine.domain.TransactionStatus;
import dev.ledger.engine.domain.TransactionType;
import dev.ledger.engine.dto.BalanceResponse;
import dev.ledger.engine.exception.AccountNotFoundException;
import dev.ledger.engine.exception.CurrencyMismatchException;
import dev.ledger.engine.exception.InsufficientFundsException;
import dev.ledger.engine.exception.InvalidTransferException;
import dev.ledger.engine.exception.TransactionNotFoundException;
import dev.ledger.engine.repository.AccountRepository;
import dev.ledger.engine.repository.EntryRepository;
import dev.ledger.engine.repository.OutboxRepository;
import dev.ledger.engine.repository.TransactionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional heart. Each post runs in one transaction with both accounts
 * locked FOR UPDATE in ascending id order (deadlock-free). The row lock is the
 * per-account mutex: the balance is read and the legs inserted while it is held,
 * so READ COMMITTED is sufficient and correct — and avoids the spurious
 * serialization aborts that SERIALIZABLE raises on the balance predicate read
 * under heavy contention. Both legs commit or neither does; the DB's deferred
 * trigger rejects any unbalanced transaction.
 */
@Component
public class TransferProcessor {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final EntryRepository entries;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public TransferProcessor(AccountRepository accounts, TransactionRepository transactions,
            EntryRepository entries, OutboxRepository outbox, ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PostResult post(String idempotencyKey, long from, long to, long amountMinor, String currency) {
        if (from == to) {
            throw new InvalidTransferException("from and to must differ");
        }
        if (amountMinor <= 0) {
            throw new InvalidTransferException("amountMinor must be greater than 0");
        }

        // Lock both account rows in ascending id order, reusing the returned rows
        // (no second fetch) — the lock is held for the rest of the transaction.
        long lo = Math.min(from, to);
        long hi = Math.max(from, to);
        Account loAcc = lockAccount(lo);
        Account hiAcc = lockAccount(hi);
        Account fromAcc = from == lo ? loAcc : hiAcc;
        Account toAcc = to == lo ? loAcc : hiAcc;

        if (!fromAcc.currency().equals(currency) || !toAcc.currency().equals(currency)) {
            throw new CurrencyMismatchException("transfer currency " + currency
                    + " must match both accounts (" + fromAcc.currency() + ", " + toAcc.currency() + ")");
        }

        long fromBalance = entries.balanceOf(from);
        // System accounts are the external boundary money enters through and may run
        // negative; every other account is overdraft-protected.
        if (!fromAcc.isSystem() && fromBalance < amountMinor) {
            throw new InsufficientFundsException(from, fromBalance, amountMinor);
        }
        long toBalance = entries.balanceOf(to);

        long fromAfter;
        long toAfter;
        try {
            fromAfter = Math.subtractExact(fromBalance, amountMinor);
            toAfter = Math.addExact(toBalance, amountMinor);
        } catch (ArithmeticException overflow) {
            throw new InvalidTransferException("transfer would overflow an account balance");
        }

        Transaction tx = transactions.insert(
                idempotencyKey, TransactionType.TRANSFER, TransactionStatus.POSTED, null);
        entries.insert(tx.id(), from, -amountMinor, EntryDirection.DEBIT, currency);
        entries.insert(tx.id(), to, amountMinor, EntryDirection.CREDIT, currency);
        outbox.insert(tx.id(), "TRANSFER_POSTED", payload(Map.of(
                "transactionId", tx.id(), "from", from, "to", to,
                "amountMinor", amountMinor, "currency", currency)));

        // Post-balances are deterministic under the row lock: no concurrent write
        // to these accounts can interleave, so derive them instead of re-summing.
        return new PostResult(tx.id(), List.of(
                new BalanceResponse(from, fromAfter, currency),
                new BalanceResponse(to, toAfter, currency)));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PostResult reverse(long originalTxId) {
        Transaction original = transactions.findById(originalTxId)
                .orElseThrow(() -> new TransactionNotFoundException(originalTxId));
        if (original.type() != TransactionType.TRANSFER) {
            throw new InvalidTransferException("only a TRANSFER can be reversed");
        }
        if (transactions.reversalExistsFor(originalTxId)) {
            throw new InvalidTransferException("transaction " + originalTxId + " is already reversed");
        }

        List<Entry> legs = entries.findByTransaction(originalTxId);
        // Lock each distinct involved account once, ascending, keeping the rows.
        Map<Long, Account> locked = new LinkedHashMap<>();
        legs.stream().map(Entry::accountId).distinct().sorted()
                .forEach(id -> locked.put(id, lockAccount(id)));

        Transaction reversal = transactions.insert(
                null, TransactionType.REVERSAL, TransactionStatus.POSTED, originalTxId);
        for (Entry leg : legs) {
            long mirrored = -leg.amountMinor();
            EntryDirection direction = mirrored > 0 ? EntryDirection.CREDIT : EntryDirection.DEBIT;
            entries.insert(reversal.id(), leg.accountId(), mirrored, direction, leg.currency());
        }
        outbox.insert(reversal.id(), "REVERSAL_POSTED",
                payload(Map.of("transactionId", reversal.id(), "reverses", originalTxId)));

        List<BalanceResponse> balances = locked.keySet().stream()
                .map(id -> new BalanceResponse(id, entries.balanceOf(id), locked.get(id).currency()))
                .toList();
        return new PostResult(reversal.id(), balances);
    }

    private Account lockAccount(long id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    private String payload(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }
}
