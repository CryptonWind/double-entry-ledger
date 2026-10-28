package com.ledger.application;

import com.ledger.application.port.out.AccountStore;
import com.ledger.application.port.in.AccountUseCases;
import com.ledger.domain.model.Account;
import com.ledger.domain.service.MoneyRules;
import com.ledger.domain.exception.LedgerException;
import com.ledger.application.model.LedgerModels.AccountView;
import com.ledger.application.model.LedgerModels.OpenAccount;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import com.ledger.application.port.out.UnitOfWork;

@RequiredArgsConstructor
@Log4j2
public class AccountService implements AccountUseCases {
    private final AccountStore accounts;
    private final UnitOfWork work;

    public AccountView open(OpenAccount request) {
        return work.required(() -> openAccount(request));
    }

    private AccountView openAccount(OpenAccount request) {
        var account = Account.open(UUID.randomUUID().toString(), request.currency(), request.overdraftLimit(), java.time.Instant.now());
        accounts.insert(account);
        log.info("Opened account accountId={} currency={} overdraftLimit={}", account.getId(), account.getCurrency(), account.getOverdraftLimit());
        return view(account);
    }

    public AccountView get(String id) {
        log.debug("Reading account accountId={}", id);
        return work.read(() -> view(accounts.find(id).orElseThrow(() ->
                new LedgerException(404, "ACCOUNT_NOT_FOUND", "accountId", "Unknown account: " + id))));
    }

    public static AccountView view(Account account) {
        int scale = MoneyRules.scale(account.getCurrency());
        return new AccountView(account.getId(), account.getCurrency(),
                account.getOverdraftLimit() == null ? null : account.getOverdraftLimit().setScale(scale),
                account.getOverdraftLimit() == null, account.getBalance().setScale(scale));
    }
}
