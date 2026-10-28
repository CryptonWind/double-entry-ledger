package com.ledger.integration;

import com.ledger.infrastructure.configuration.MessagingConfiguration;
import com.ledger.application.IdempotencyService;
import com.ledger.infrastructure.output.messaging.ConfirmedPublisher;
import com.ledger.infrastructure.input.scheduler.OutboxPublisher;
import com.ledger.application.model.LedgerModels.*;
import com.ledger.application.LedgerService;
import com.ledger.domain.model.Direction;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import jakarta.persistence.EntityManager;
import com.ledger.application.AccountHistoryService;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class LedgerIntegrationTest extends com.ledger.support.IntegrationTestBase {

    @Test
    void accountRetryAcrossInstancesAndDifferentPayloadConflict() {
        String key = key();
        var request = new OpenAccount("EUR", null);
        var first = idempotency.execute("open-account", key, request, () -> accounts.open(request));
        var other = second.getBean(IdempotencyService.class);
        var retry = other.execute("open-account", key, request, () -> { throw new AssertionError("Must replay"); });
        assertThat(retry).isEqualTo(first);
        try {
            other.execute("open-account", key, new OpenAccount("USD", null), () -> null);
            throw new AssertionError("Expected conflict");
        } catch (com.ledger.domain.exception.LedgerException e) {
            assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        }
    }

    @Test
    void concurrentIdenticalRequestsHaveOneEffectAcrossInstances() throws Exception {
        String target = account("EUR");
        var request = transfer("settlement-EUR", target, "1.00");
        String key = key();
        var other = second.getBean(IdempotencyService.class);
        var otherLedger = second.getBean(LedgerService.class);
        var replies = race(16, index -> (index % 2 == 0 ? idempotency : other)
                .execute("record-transaction", key, request, () -> (index % 2 == 0 ? ledger : otherLedger).record(request)));
        assertThat(replies).allMatch(replies.get(0)::equals);
        assertThat(accounts.get(target).balance()).isEqualByComparingTo("1.00");
        assertIntegrity();
    }

    @Test
    void concurrentDebitsNeverOverdrawOrLoseUpdatesAcrossInstances() throws Exception {
        String source = account("EUR");
        String target = account("EUR");
        record(transfer("settlement-EUR", source, "10.00"));
        var request = transfer(source, target, "1.00");
        var other = second.getBean(IdempotencyService.class);
        var otherLedger = second.getBean(LedgerService.class);
        var replies = race(20, index -> (index % 2 == 0 ? idempotency : other)
                .execute("record-transaction", key(), request, () -> (index % 2 == 0 ? ledger : otherLedger).record(request)));
        assertThat(replies.stream().filter(r -> r.status() == 201).count()).isEqualTo(10);
        assertThat(replies.stream().filter(r -> r.status() == 422).count()).isEqualTo(10);
        assertThat(accounts.get(source).balance()).isEqualByComparingTo("0.00");
        assertThat(accounts.get(target).balance()).isEqualByComparingTo("10.00");
        assertIntegrity();
    }

    @Test
    void oppositeTransfersAcquireLocksInTheSameOrder() throws Exception {
        String a = account("EUR");
        String b = account("EUR");
        record(transfer("settlement-EUR", a, "50.00"));
        record(transfer("settlement-EUR", b, "50.00"));
        var replies = race(20, index -> record(index % 2 == 0 ? transfer(a, b, "1.00") : transfer(b, a, "1.00")));
        assertThat(replies).allMatch(r -> r.status() == 201);
        assertThat(accounts.get(a).balance()).isEqualByComparingTo("50.00");
        assertThat(accounts.get(b).balance()).isEqualByComparingTo("50.00");
        assertIntegrity();
    }

    @Test
    void refusedTransactionLeavesNoFinancialRowsAndReplaysOriginalError() {
        String a = account("EUR");
        String b = account("EUR");
        long count = transactionCount();
        long entryCount = database.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
        long eventCount = database.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class);
        String key = key();
        var request = transfer(a, b, "5.00");
        var rejected = idempotency.execute("record-transaction", key, request, () -> ledger.record(request));
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(transactionCount()).isEqualTo(count);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class)).isEqualTo(entryCount);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isEqualTo(eventCount);
        record(transfer("settlement-EUR", a, "10.00"));
        assertThat(idempotency.execute("record-transaction", key, request, () -> ledger.record(request))).isEqualTo(rejected);
        assertThat(accounts.get(a).balance()).isEqualByComparingTo("10.00");
        assertIntegrity();
    }

    @Test
    void multiEntryTransactionAndConcurrentReversalAreAtomic() throws Exception {
        String merchant = account("EUR");
        String payout = account("EUR");
        String fees = account("EUR");
        record(transfer("settlement-EUR", merchant, "125.50"));
        var request = new RecordTransaction("Payout and fee", List.of(
                new EntryInput(merchant, Direction.DEBIT, new BigDecimal("100.00")),
                new EntryInput(payout, Direction.CREDIT, new BigDecimal("98.50")),
                new EntryInput(fees, Direction.CREDIT, new BigDecimal("1.50"))));
        var original = json.read(record(request).body(), TransactionView.class);
        var replies = race(2, index -> idempotency.execute("reverse-transaction", key(), original.id(), () -> ledger.reverse(original.id())));
        assertThat(replies.stream().map(IdempotencyService.StoredResponse::status).sorted().toList()).containsExactly(201, 409);
        assertThat(accounts.get(merchant).balance()).isEqualByComparingTo("125.50");
        assertThat(accounts.get(payout).balance()).isEqualByComparingTo("0.00");
        assertThat(ledger.get(original.id())).isEqualTo(original);
        var reversal = json.read(replies.stream().filter(r -> r.status() == 201).findFirst().orElseThrow().body(), TransactionView.class);
        var otherIdempotency = second.getBean(IdempotencyService.class);
        var replayKey = key();
        var replay = idempotency.execute("reverse-transaction", replayKey, reversal.id(), () -> ledger.reverse(reversal.id()));
        assertThat(otherIdempotency.execute("reverse-transaction", replayKey, reversal.id(),
                () -> { throw new AssertionError("Must replay the original reversal refusal"); })).isEqualTo(replay);
        assertThat(idempotency.execute("reverse-transaction", key(), reversal.id(), () -> ledger.reverse(reversal.id())).status()).isEqualTo(422);
        assertIntegrity();
    }

    @Test
    void reversalRespectsOverdraft() {
        String a = account("EUR");
        String b = account("EUR");
        var original = json.read(record(transfer("settlement-EUR", a, "10.00")).body(), TransactionView.class);
        record(transfer(a, b, "10.00"));
        long before = transactionCount();
        var refused = idempotency.execute("reverse-transaction", key(), original.id(), () -> ledger.reverse(original.id()));
        assertThat(refused.status()).isEqualTo(422);
        assertThat(transactionCount()).isEqualTo(before);
        assertIntegrity();
    }

    @Test
    void repeatedAccountUsesNetEffectAndConfiguredOverdraft() {
        var open = new OpenAccount("USD", new BigDecimal("5.00"));
        String a = json.read(idempotency.execute("open-account", key(), open, () -> accounts.open(open)).body(), AccountView.class).id();
        String b = account("USD");
        var request = new RecordTransaction("Net effect", List.of(
                new EntryInput(a, Direction.DEBIT, new BigDecimal("10.00")),
                new EntryInput(a, Direction.CREDIT, new BigDecimal("5.00")),
                new EntryInput(b, Direction.CREDIT, new BigDecimal("5.00"))));
        assertThat(record(request).status()).isEqualTo(201);
        assertThat(accounts.get(a).balance()).isEqualByComparingTo("-5.00");
        assertThat(record(transfer(a, b, "0.01")).status()).isEqualTo(422);
        assertIntegrity();
    }

    @Test
    void samePaymentRacingAcrossInstancesIsRecordedOnce() throws Exception {
        String merchant = account("EUR");
        String paymentId = key();
        var other = second.getBean(com.ledger.application.PaymentService.class);
        var local = payments;
        var replies = race(12, index -> (index % 2 == 0 ? local : other).settle(new com.ledger.application.PaymentService.PaymentSettled(
                key(), paymentId, merchant, new BigDecimal("7.50"), "EUR")));
        assertThat(replies).allMatch(replies.get(0)::equals);
        assertThat(accounts.get(merchant).balance()).isEqualByComparingTo("7.50");
        assertIntegrity();
    }

    @Test
    void validationRejectsCurrencyPrecisionUnknownAccountsAndUnbalancedEntries() throws Exception {
        String eur = account("EUR");
        String jpy = account("JPY");
        assertThat(record(transfer("settlement-JPY", jpy, "100.5")).body()).contains("INVALID_PRECISION");
        assertThat(record(transfer(eur, jpy, "1")).body()).contains("MIXED_CURRENCIES");
        assertThat(record(transfer("missing", eur, "1.00")).body()).contains("UNKNOWN_ACCOUNT");
        var unbalanced = new RecordTransaction("Unbalanced", List.of(
                new EntryInput("settlement-EUR", Direction.DEBIT, new BigDecimal("1.00")),
                new EntryInput(eur, Direction.CREDIT, new BigDecimal("0.99"))));
        assertThat(record(unbalanced).body()).contains("UNBALANCED_TRANSACTION");
        assertThat(post("/api/v1/accounts", key(), "{\"currency\":\"ABC\"}").body()).contains("INVALID_CURRENCY");
        assertThat(post("/api/v1/accounts", key(), "{\"currency\":\"GBP\"}").body()).contains("UNSUPPORTED_CURRENCY");
        assertThat(post("/api/v1/transactions", key(), "{\"description\":\"x\",\"entries\":[]}").statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/accounts", key(), "{\"currency\":\"EUR\",\"unexpected\":1}").statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/accounts", key(), "{\"currency\":\"EUR\"} {\"currency\":\"USD\"}").statusCode()).isEqualTo(400);
        assertThat(record(transfer("settlement-EUR", eur, "0.00")).body()).contains("INVALID_AMOUNT");
        assertThat(record(transfer("settlement-EUR", eur, "-1.00")).body()).contains("INVALID_AMOUNT");
        assertIntegrity();
    }

    @Test
    void exactAmountsAndHttpRetry() throws Exception {
        String eur = account("EUR");
        String key = key();
        String body = json.write(transfer("settlement-EUR", eur, "0.10"));
        var first = post("/api/v1/transactions", key, body);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(post("/api/v1/transactions", key, body).body()).isEqualTo(first.body());
        record(transfer("settlement-EUR", eur, "0.20"));
        assertThat(accounts.get(eur).balance()).isEqualByComparingTo("0.30");
        var view = json.read(first.body(), TransactionView.class);
        var read = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transactions/" + view.id())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(json.read(read.body(), TransactionView.class)).isEqualTo(view);
        assertIntegrity();
    }

    @Test
    void paymentRedeliveryWithDifferentEventIdsAndPoisonMessages() {
        String merchant = account("JPY");
        String paymentId = key();
        long failed = database.queryForObject("SELECT COUNT(*) FROM failed_payment_messages", Long.class);
        publisher.publish("payments", "payment.settled", key(), "{broken json");
        for (int i = 0; i < 5; i++) {
            publisher.publish("payments", "payment.settled", key(), json.write(Map.of(
                    "eventId", key(), "paymentId", paymentId, "merchantAccountId", merchant,
                    "amount", "15000", "currency", "JPY")));
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(accounts.get(merchant).balance()).isEqualByComparingTo("15000");
            assertThat(database.queryForObject("SELECT COUNT(*) FROM failed_payment_messages", Long.class)).isGreaterThan(failed);
            assertThat(database.queryForObject("SELECT COUNT(*) FROM processed_payments WHERE payment_id = ?", Long.class, paymentId)).isEqualTo(1);
        });
        publisher.publish("payments", "payment.settled", key(), json.write(Map.of(
                "eventId", key(), "paymentId", paymentId, "merchantAccountId", merchant,
                "amount", "16000", "currency", "JPY")));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(database.queryForObject("SELECT COUNT(*) FROM failed_payment_messages", Long.class)).isGreaterThan(failed + 1));
        assertThat(accounts.get(merchant).balance()).isEqualByComparingTo("15000");
        assertThat(rabbit.receive("ledger.payment-settled.dlq", 5000)).isNotNull();
        assertIntegrity();
    }

    @Test
    void rollbackBeforeCommitHasNoEffectAndRetryCanSucceed() {
        String a = account("EUR");
        var request = transfer("settlement-EUR", a, "2.00");
        long before = transactionCount();
        String key = key();
        try {
            idempotency.execute("record-transaction", key, request, () -> {
                ledger.record(request);
                throw new IllegalStateException("Simulated failure before commit");
            });
            throw new AssertionError("Expected failure");
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("Simulated");
        }
        assertThat(transactionCount()).isEqualTo(before);
        assertThat(accounts.get(a).balance()).isEqualByComparingTo("0.00");
        assertThat(idempotency.execute("record-transaction", key, request, () -> ledger.record(request)).status()).isEqualTo(201);
        assertIntegrity();
    }

    @Test
    void outboxCanBeResumedByAnotherInstanceAndDuplicatePublicationHasStableId() {
        String a = account("EUR");
        var result = json.read(record(transfer("settlement-EUR", a, "3.00")).body(), TransactionView.class);
        var event = database.queryForMap("SELECT id, payload FROM outbox_events WHERE payload LIKE ?", "%" + result.id() + "%");
        String eventId = (String) event.get("id");
        // Simulate publish followed by crash before the published_at update.
        publisher.publish(MessagingConfiguration.TRANSACTION_EXCHANGE, "transaction.recorded", eventId, (String) event.get("payload"));
        var other = new OutboxPublisher(second.getBean(EntityManager.class), second.getBean(TransactionTemplate.class),
                second.getBean(ConfirmedPublisher.class));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            other.publishPending();
            assertThat(database.queryForObject("SELECT published_at FROM outbox_events WHERE id = ?",
                    java.sql.Timestamp.class, eventId)).isNotNull();
        });
        List<String> matchingIds = new ArrayList<>();
        Message message;
        while ((message = rabbit.receive("ledger.transaction-recorded", 1000)) != null) {
            if (eventId.equals(message.getMessageProperties().getMessageId())) matchingIds.add(eventId);
        }
        assertThat(matchingIds).hasSize(2);
        assertThat(database.queryForObject("SELECT published_at FROM outbox_events WHERE id = ?", java.sql.Timestamp.class, eventId)).isNotNull();
    }

    @Test
    void databaseRejectsHistoryUpdatesAndDeletes() {
        var original = json.read(record(transfer("settlement-EUR", account("EUR"), "1.00")).body(), TransactionView.class);
        assertThatThrownBy(() -> database.update("UPDATE ledger_transactions SET description = 'changed' WHERE id = ?", original.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> database.update("DELETE FROM ledger_transactions WHERE id = ?", original.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        String entryId = original.entries().get(0).id();
        assertThatThrownBy(() -> database.update("UPDATE ledger_entries SET amount = 2 WHERE id = ?", entryId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> database.update("DELETE FROM ledger_entries WHERE id = ?", entryId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(ledger.get(original.id())).isEqualTo(original);
        assertIntegrity();
    }

    @Test
    void entriesArePagedNewestFirstAndHistoricalBalanceUsesInclusivePostingTime() {
        String account = account("EUR");
        var first = json.read(record(transfer("settlement-EUR", account, "10.00")).body(), TransactionView.class);
        var secondPosting = json.read(record(transfer("settlement-EUR", account, "2.50")).body(), TransactionView.class);
        var page = history.entries(account, 0, 1, secondPosting.recordedAt());
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).singleElement().satisfies(entry -> {
            assertThat(entry.transactionId()).isEqualTo(secondPosting.id());
            assertThat(entry.balanceAfter()).isEqualByComparingTo("12.50");
        });
        assertThat(history.entries(account, 1, 1, page.asOf()).content()).singleElement()
                .satisfies(entry -> assertThat(entry.transactionId()).isEqualTo(first.id()));
        assertThat(history.balance(account, first.recordedAt().minusNanos(1000)).balance()).isEqualByComparingTo("0.00");
        assertThat(history.balance(account, first.recordedAt()).balance()).isEqualByComparingTo("10.00");
        assertThat(history.balance(account, secondPosting.recordedAt()).balance()).isEqualByComparingTo("12.50");
        assertThatThrownBy(() -> history.entries(account, -1, 50, null))
                .isInstanceOf(com.ledger.domain.exception.LedgerException.class);
    }

    @Test
    void repeatedAccountEntriesHaveRunningBalances() {
        String a = account("EUR");
        var request = new RecordTransaction("Repeated account", List.of(
                new EntryInput("settlement-EUR", Direction.DEBIT, new BigDecimal("3.00")),
                new EntryInput(a, Direction.CREDIT, new BigDecimal("1.00")),
                new EntryInput(a, Direction.CREDIT, new BigDecimal("2.00"))));
        var result = json.read(record(request).body(), TransactionView.class);
        assertThat(result.entries().get(1).balanceAfter()).isEqualByComparingTo("1.00");
        assertThat(result.entries().get(2).balanceAfter()).isEqualByComparingTo("3.00");
        assertThat(history.entries(a, 0, 50, result.recordedAt()).content())
                .extracting(AccountHistoryService.HistoryEntry::position).containsExactly(2, 1);
    }

    @Test
    void httpTraceContextIsReturnedAndPersistedInAnnouncements() throws Exception {
        String a = account("EUR");
        String correlationId = key();
        String traceId = "1234567890abcdef1234567890abcdef";
        String traceparent = "00-" + traceId + "-1234567890abcdef-01";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transactions"))
                .header("Content-Type", "application/json").header("Idempotency-Key", key())
                .header("X-Correlation-ID", correlationId).header("traceparent", traceparent)
                .POST(HttpRequest.BodyPublishers.ofString(json.write(transfer("settlement-EUR", a, "1.00")))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(correlationId);
        assertThat(response.headers().firstValue("traceparent").orElseThrow()).startsWith("00-" + traceId + "-");
        var transaction = json.read(response.body(), TransactionView.class);
        var stored = database.queryForMap("SELECT correlation_id, traceparent, payload FROM outbox_events WHERE payload LIKE ?",
                "%" + transaction.id() + "%");
        assertThat(stored.get("correlation_id")).isEqualTo(correlationId);
        assertThat((String) stored.get("traceparent")).startsWith("00-" + traceId + "-");
        assertThat((String) stored.get("payload")).contains(correlationId, traceId);
        var contract = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/openapi.yaml")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(contract.statusCode()).isEqualTo(200);
        assertThat(contract.body()).contains("openapi: 3.0.3", "getHistoricalBalance", "getAccountEntries");
    }

    @Test
    void generatedOpenApiDocumentsAllBusinessOperationsAndSwaggerUiIsServed() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("openAccount", "getAccount", "getAccountEntries", "getHistoricalBalance",
                "recordTransaction", "getTransaction", "reverseTransaction", "Idempotency-Key", "X-Correlation-ID", "traceparent");
        var ui = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/swagger-ui/index.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("Swagger UI");
    }

    @Test
    void paymentTraceSurvivesOutboxPublication() {
        String merchant = account("EUR");
        String paymentId = key();
        String sourceEventId = key();
        String correlationId = key();
        String traceId = "abcdef1234567890abcdef1234567890";
        try (var ignored = com.ledger.infrastructure.tracing.TraceContext.open(correlationId,
                "00-" + traceId + "-abcdef1234567890-01")) {
            publisher.publish("payments", "payment.settled", sourceEventId, json.write(Map.of(
                    "eventId", sourceEventId, "paymentId", paymentId, "merchantAccountId", merchant,
                    "amount", "1.00", "currency", "EUR")));
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(accounts.get(merchant).balance()).isEqualByComparingTo("1.00"));
        var stored = database.queryForMap("SELECT id, correlation_id, payload FROM outbox_events WHERE payload LIKE ?",
                "%" + paymentId + "%");
        assertThat(stored.get("correlation_id")).isEqualTo(correlationId);
        assertThat((String) stored.get("payload")).contains(paymentId, sourceEventId, traceId);
        var worker = new OutboxPublisher(second.getBean(EntityManager.class), second.getBean(TransactionTemplate.class),
                second.getBean(ConfirmedPublisher.class));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            worker.publishPending();
            assertThat(database.queryForObject("SELECT published_at FROM outbox_events WHERE id = ?",
                    java.sql.Timestamp.class, stored.get("id"))).isNotNull();
        });
        Message found = null;
        Message candidate;
        while ((candidate = rabbit.receive("ledger.transaction-recorded", 1000)) != null) {
            if (stored.get("id").equals(candidate.getMessageProperties().getMessageId())) found = candidate;
        }
        assertThat(found).isNotNull();
        assertThat(found.getMessageProperties().getCorrelationId()).isEqualTo(correlationId);
        assertThat((String) found.getMessageProperties().getHeaders().get("traceparent")).startsWith("00-" + traceId + "-");
    }

}
