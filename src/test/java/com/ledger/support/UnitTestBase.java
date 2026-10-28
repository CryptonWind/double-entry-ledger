package com.ledger.support;

import com.ledger.infrastructure.output.persistence.*;
import com.ledger.application.*;
import com.ledger.infrastructure.output.messaging.*;
import com.ledger.infrastructure.input.scheduler.OutboxPublisher;
import com.ledger.infrastructure.input.messaging.PaymentConsumer;
import com.ledger.infrastructure.output.serialization.JsonSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** One cached minimal Spring context; no Boot auto-configuration, database or broker. */
@SpringJUnitConfig(UnitTestBase.UnitConfiguration.class)
public abstract class UnitTestBase {
    @TestConfiguration(proxyBeanMethods = false)
    static class UnitConfiguration {
        @Bean EntityManager entityManager() { return mock(EntityManager.class); }
        @Bean AccountRepository accountRepository() { return mock(AccountRepository.class); }
        @Bean LedgerTransactionRepository transactionRepository() { return mock(LedgerTransactionRepository.class); }
        @Bean LedgerEntryRepository entryRepository() { return mock(LedgerEntryRepository.class); }
        @Bean HibernateDatabase database() { return mock(HibernateDatabase.class); }
        @Bean TransactionTemplate transactionTemplate() { return mock(TransactionTemplate.class); }
        @Bean RabbitTemplate rabbitTemplate() { return mock(RabbitTemplate.class); }
        @Bean JsonSupport jsonSupport() { return new JsonSupport(JsonMapper.builder().build()); }
        @Bean AccountService accountService(AccountRepository accounts, EntityManager entities) {
            return new AccountService(new JpaAccountAdapter(accounts, entities), directWork());
        }
        @Bean AccountHistoryService historyService(AccountService accounts, EntityManager entities) {
            return new AccountHistoryService(accounts, new JpaAccountHistoryAdapter(entities), directWork());
        }
        @Bean DefaultLedgerService ledgerService(AccountRepository accounts, LedgerTransactionRepository transactions,
                LedgerEntryRepository entries, EntityManager entities, JsonSupport json) {
            var accountAdapter = new JpaAccountAdapter(accounts, entities);
            return new DefaultLedgerService(accountAdapter,
                    new JpaTransactionAdapter(transactions, entries, accountAdapter, entities),
                    new OutboxAnnouncementAdapter(entities, json), directWork());
        }
        @Bean DatabaseTransactions databaseTransactions(TransactionTemplate template) { return new DatabaseTransactions(template); }
        @Bean IdempotencyService idempotencyService(HibernateDatabase database, EntityManager entities,
                JsonSupport json, DatabaseTransactions transactions) {
            return new IdempotencyService(new JpaIdempotencyAdapter(database, entities),
                    json, transactions);
        }
        @Bean PaymentService paymentService(DatabaseTransactions transactions, HibernateDatabase database,
                EntityManager entities, JsonSupport json, DefaultLedgerService ledger, AccountRepository accounts) {
            return new PaymentService(transactions,
                    new JpaPaymentAdapter(database, entities),
                    new JpaAccountAdapter(accounts, entities), json, ledger);
        }
        @Bean ConfirmedPublisher confirmedPublisher(RabbitTemplate rabbit) { return new ConfirmedPublisher(rabbit); }
        @Bean OutboxPublisher outboxPublisher(EntityManager entities, TransactionTemplate template, ConfirmedPublisher publisher) {
            return new OutboxPublisher(entities, template, publisher);
        }
        @Bean PaymentConsumer paymentConsumer(PaymentService payments, JsonSupport json, EntityManager entities,
                DatabaseTransactions transactions, ConfirmedPublisher publisher) {
            return new PaymentConsumer(payments, json, entities, transactions, publisher);
        }
    }

    protected static com.ledger.application.port.out.UnitOfWork directWork() {
        return new com.ledger.application.port.out.UnitOfWork() {
            @Override public <T> T execute(java.util.function.Supplier<T> action) { return action.get(); }
        };
    }

    @Autowired protected EntityManager entities;
    @Autowired protected AccountRepository accountRepository;
    @Autowired protected LedgerTransactionRepository transactionRepository;
    @Autowired protected LedgerEntryRepository entryRepository;
    @Autowired protected HibernateDatabase database;
    @Autowired protected TransactionTemplate template;
    @Autowired protected RabbitTemplate rabbit;
    @Autowired protected JsonSupport json;
    @Autowired protected AccountService accounts;
    @Autowired protected AccountHistoryService history;
    @Autowired protected DefaultLedgerService ledger;
    @Autowired protected DatabaseTransactions transactions;
    @Autowired protected IdempotencyService idempotency;
    @Autowired protected PaymentService payments;
    @Autowired protected ConfirmedPublisher publisher;
    @Autowired protected OutboxPublisher outbox;
    @Autowired protected PaymentConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void resetInfrastructureMocks() {
        reset(entities, accountRepository, transactionRepository, entryRepository, database, template, rabbit);
        MDC.clear();
        when(template.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
    }
}
