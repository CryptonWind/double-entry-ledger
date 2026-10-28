package com.ledger.integration;

import com.ledger.application.model.LedgerModels.TransactionView;
import com.ledger.infrastructure.output.persistence.OutboxEvent;
import com.ledger.infrastructure.input.scheduler.OutboxPublisher;
import com.ledger.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class FailureRecoveryIntegrationTest extends IntegrationTestBase {
    @Test
    void standaloneUseCasesEstablishTheirOwnTransactions() {
        var account = accounts.open(new com.ledger.application.model.LedgerModels.OpenAccount("EUR", null));
        var posted = ledger.record(transfer("settlement-EUR", account.id(), "125.50"));
        assertThat(accounts.get(account.id()).balance()).isEqualByComparingTo("125.50");
        ledger.reverse(posted.id());
        assertThat(accounts.get(account.id()).balance()).isEqualByComparingTo("0.00");
        assertIntegrity();
    }
    @Test
    void brokerConfirmationDoesNotHoldDatabaseTransaction() {
        String merchant = account("EUR");
        record(transfer("settlement-EUR", merchant, "1.00"));
        var confirming = new com.ledger.infrastructure.output.messaging.ConfirmedPublisher(rabbit) {
            @Override public void publish(String exchange, String routing, String id, String payload) {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                super.publish(exchange, routing, id, payload);
            }
        };
        new OutboxPublisher(entities, transactionTemplate, confirming).publishPending();
        assertIntegrity();
    }
    @Test
    void unexpiredLeaseIsSkippedAndExpiredLeaseCanBeRecoveredByAnotherWorker() {
        String merchant = account("EUR");
        var transaction = json.read(record(transfer("settlement-EUR", merchant, "1.00")).body(), TransactionView.class);
        var row = database.queryForMap("SELECT id FROM outbox_events WHERE payload LIKE ?", "%" + transaction.id() + "%");
        String id = (String) row.get("id");
        database.update("UPDATE outbox_events SET lease_token='crashed-worker', lease_until=TIMESTAMPADD(MINUTE,5,UTC_TIMESTAMP(6)) WHERE id=?", id);
        var worker = new OutboxPublisher(entities, transactionTemplate, publisher);
        worker.publishPending();
        assertThat(database.queryForObject("SELECT published_at FROM outbox_events WHERE id=?", java.sql.Timestamp.class, id)).isNull();
        database.update("UPDATE outbox_events SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?", id);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            worker.publishPending();
            assertThat(database.queryForObject("SELECT published_at FROM outbox_events WHERE id=?", java.sql.Timestamp.class, id)).isNotNull();
        });
    }
    @Test
    void unknownAndWrongCurrencyPaymentsAreQuarantinedBeforeFollowingValidPayment() {
        String merchant = account("EUR");
        long failuresBefore = database.queryForObject("SELECT COUNT(*) FROM failed_payment_messages", Long.class);
        publisher.publish("payments", "payment.settled", key(), json.write(Map.of(
                "eventId", key(), "paymentId", key(), "merchantAccountId", "unknown-" + key(), "amount", "125.50", "currency", "EUR")));
        publisher.publish("payments", "payment.settled", key(), json.write(Map.of(
                "eventId", key(), "paymentId", key(), "merchantAccountId", merchant, "amount", "15000", "currency", "JPY")));
        publisher.publish("payments", "payment.settled", key(), json.write(Map.of(
                "eventId", key(), "paymentId", key(), "merchantAccountId", merchant, "amount", "125.50", "currency", "EUR")));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(accounts.get(merchant).balance()).isEqualByComparingTo("125.50");
            assertThat(database.queryForObject("SELECT COUNT(*) FROM failed_payment_messages", Long.class)).isEqualTo(failuresBefore + 2);
        });
        assertIntegrity();
    }

    @Test
    void financialCommitSurvivesUnroutableAnnouncementAndPublicationCanResume() {
        String merchant = account("EUR");
        var transaction = json.read(record(transfer("settlement-EUR", merchant, "125.50")).body(), TransactionView.class);
        var row = database.queryForMap("SELECT id, payload FROM outbox_events WHERE payload LIKE ?", "%" + transaction.id() + "%");
        String id = (String) row.get("id");
        var admin = new RabbitAdmin(rabbit.getConnectionFactory());
        String exchange = "test-unroutable-" + key();
        var topic = new TopicExchange(exchange, false, true);
        admin.declareExchange(topic);
        try {
            assertThatThrownBy(() -> transactionTemplate.execute(status -> {
                var pending = entities.find(OutboxEvent.class, id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                publisher.publish(exchange, "transaction.recorded", id, pending.getPayload());
                pending.published();
                return null;
            })).isInstanceOf(IllegalStateException.class);
            assertThat(database.queryForObject("SELECT published_at FROM outbox_events WHERE id = ?", java.sql.Timestamp.class, id)).isNull();
            assertThat(accounts.get(merchant).balance()).isEqualByComparingTo(new BigDecimal("125.50"));
            var worker = new OutboxPublisher(entities, transactionTemplate, publisher);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                worker.publishPending();
                assertThat(database.queryForObject("SELECT published_at FROM outbox_events WHERE id = ?", java.sql.Timestamp.class, id)).isNotNull();
            });
        } finally {
            admin.deleteExchange(exchange);
        }
        assertIntegrity();
    }
}
