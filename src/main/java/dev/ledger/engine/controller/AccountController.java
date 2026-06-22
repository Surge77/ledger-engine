package dev.ledger.engine.controller;

import dev.ledger.engine.domain.Account;
import dev.ledger.engine.dto.AccountResponse;
import dev.ledger.engine.dto.BalanceResponse;
import dev.ledger.engine.dto.CreateAccountRequest;
import dev.ledger.engine.dto.DepositRequest;
import dev.ledger.engine.dto.DepositResponse;
import dev.ledger.engine.dto.EntryResponse;
import dev.ledger.engine.dto.PageResponse;
import dev.ledger.engine.service.AccountService;
import dev.ledger.engine.service.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/accounts")
public class AccountController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    private final AccountService accountService;
    private final LedgerService ledger;

    public AccountController(AccountService accountService, LedgerService ledger) {
        this.accountService = accountService;
        this.ledger = ledger;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.create(request.name(), request.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<DepositResponse> deposit(
            @PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = false)
            @Size(max = MAX_IDEMPOTENCY_KEY_LENGTH, message = "Idempotency-Key is too long")
            String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ledger.deposit(idempotencyKey, id, request));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable long id) {
        return AccountResponse.from(accountService.get(id));
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@PathVariable long id) {
        return ledger.balance(id);
    }

    @GetMapping("/{id}/entries")
    public PageResponse<EntryResponse> entries(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ledger.entries(id, safePage, safeSize);
    }
}
