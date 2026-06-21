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
import java.util.stream.LongStream;
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

        lockInOrder(from, to);
        Account fromAcc = require(from);
        Account toAcc = require(to);

        if (!fromAcc.currency().equals(currency) || !toAcc.currency().equals(currency)) {
            throw new CurrencyMismatchException("transfer currency " + currency
                    + " must match both accounts (" + fromAcc.currency() + ", " + toAcc.currency() + ")");
        }

        long fromBalance = entries.balanceOf(from);
        if (fromBalance < amountMinor) {
            throw new InsufficientFundsException(from, fromBalance, amountMinor);
        }

        Transaction tx = transactions.insert(
                idempotencyKey, TransactionType.TRANSFER, TransactionStatus.POSTED, null);
        entries.insert(tx.id(), from, -amountMinor, EntryDirection.DEBIT, currency);
        entries.insert(tx.id(), to, amountMinor, EntryDirection.CREDIT, currency);
        outbox.insert(tx.id(), "TRANSFER_POSTED", payload(Map.of(
                "transactionId", tx.id(), "from", from, "to", to,
                "amountMinor", amountMinor, "currency", currency)));

        return new PostResult(tx.id(), List.of(
                new BalanceResponse(from, entries.balanceOf(from), currency),
                new BalanceResponse(to, entries.balanceOf(to), currency)));
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
        legs.stream().map(Entry::accountId).distinct().sorted()
                .forEach(id -> accounts.findByIdForUpdate(id)
                        .orElseThrow(() -> new AccountNotFoundException(id)));

        Transaction reversal = transactions.insert(
                null, TransactionType.REVERSAL, TransactionStatus.POSTED, originalTxId);
        for (Entry leg : legs) {
            long mirrored = -leg.amountMinor();
            EntryDirection direction = mirrored > 0 ? EntryDirection.CREDIT : EntryDirection.DEBIT;
            entries.insert(reversal.id(), leg.accountId(), mirrored, direction, leg.currency());
        }
        outbox.insert(reversal.id(), "REVERSAL_POSTED",
                payload(Map.of("transactionId", reversal.id(), "reverses", originalTxId)));

        List<BalanceResponse> balances = legs.stream()
                .map(Entry::accountId).distinct().sorted()
                .map(id -> new BalanceResponse(id, entries.balanceOf(id), accountCurrency(id)))
                .toList();
        return new PostResult(reversal.id(), balances);
    }

    private void lockInOrder(long a, long b) {
        long[] ordered = LongStream.of(Math.min(a, b), Math.max(a, b)).toArray();
        for (long id : ordered) {
            accounts.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
        }
    }

    private Account require(long id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    private String accountCurrency(long id) {
        return require(id).currency();
    }

    private String payload(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }
}
