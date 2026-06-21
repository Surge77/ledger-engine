package dev.ledger.engine.service;

import dev.ledger.engine.domain.Account;
import dev.ledger.engine.exception.AccountNotFoundException;
import dev.ledger.engine.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public Account create(String name, String currency) {
        return accounts.insert(name.trim(), currency);
    }

    @Transactional(readOnly = true)
    public Account get(long id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
