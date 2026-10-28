package com.ledger.infrastructure.configuration;

import com.ledger.application.AccountHistoryService;
import com.ledger.application.AccountService;
import com.ledger.application.port.in.AccountUseCases;
import com.ledger.application.port.out.AccountHistoryReader;
import com.ledger.application.port.out.AccountStore;
import com.ledger.application.IdempotencyService;
import com.ledger.application.port.out.IdempotencyStore;
import com.ledger.application.PaymentService;
import com.ledger.application.port.out.PaymentStore;
import com.ledger.application.port.out.JsonCodec;
import com.ledger.application.port.out.UnitOfWork;
import com.ledger.application.LedgerService;
import com.ledger.application.port.in.TransactionUseCases;
import com.ledger.application.port.out.TransactionAnnouncements;
import com.ledger.application.port.out.TransactionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root: Spring wires framework-independent use cases to external adapters. */
@Configuration(proxyBeanMethods = false)
public class UseCaseConfiguration {
    @Bean AccountService accountService(AccountStore store, UnitOfWork work) {
        return new AccountService(store, work);
    }
    @Bean AccountHistoryService accountHistoryService(AccountUseCases accounts, AccountHistoryReader reader, UnitOfWork work) {
        return new AccountHistoryService(accounts, reader, work);
    }
    @Bean LedgerService ledgerService(AccountStore accounts, TransactionStore transactions,
            TransactionAnnouncements announcements, UnitOfWork work) {
        return new LedgerService(accounts, transactions, announcements, work);
    }
    @Bean PaymentService paymentService(UnitOfWork work, PaymentStore payments, AccountStore accounts,
            JsonCodec json, TransactionUseCases ledger) {
        return new PaymentService(work, payments, accounts, json, ledger);
    }
    @Bean IdempotencyService idempotencyService(IdempotencyStore store, JsonCodec json, UnitOfWork work) {
        return new IdempotencyService(store, json, work);
    }
}
