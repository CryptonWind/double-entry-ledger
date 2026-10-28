package com.ledger.integration;

import com.ledger.application.PaymentService.PaymentSettled;
import com.ledger.support.IntegrationTestBase;
import com.ledger.application.model.LedgerModels.*;
import com.ledger.domain.model.Direction;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Literal examples from pages 2-3 of the challenge, using real persistence and messaging. */
class DocumentExamplesIntegrationTest extends IntegrationTestBase {
    @Test
    void settled12550Payout9850Fee150ThenReversal() {
        String merchant42 = account("EUR");
        String payouts = account("EUR");
        String fees = account("EUR");
        BigDecimal settlementBefore = accounts.get("settlement-EUR").balance();
        String paymentId = key();
        publisher.publish("payments", "payment.settled", key(), json.write(new PaymentSettled(
                key(), paymentId, merchant42, new BigDecimal("125.50"), "EUR")));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(accounts.get(merchant42).balance()).isEqualByComparingTo("125.50"));
        assertThat(accounts.get("settlement-EUR").balance().subtract(settlementBefore)).isEqualByComparingTo("-125.50");

        var request = new RecordTransaction("Payout with fee", List.of(
                entry(merchant42, Direction.DEBIT, "100.00"),
                entry(payouts, Direction.CREDIT, "98.50"), entry(fees, Direction.CREDIT, "1.50")));
        var original = json.read(record(request).body(), TransactionView.class);
        assertThat(accounts.get(merchant42).balance()).isEqualByComparingTo("25.50");
        assertThat(accounts.get(payouts).balance()).isEqualByComparingTo("98.50");
        assertThat(accounts.get(fees).balance()).isEqualByComparingTo("1.50");

        var unbalanced = new RecordTransaction("Unbalanced document example", List.of(
                entry(merchant42, Direction.DEBIT, "100.00"), entry(payouts, Direction.CREDIT, "99.00")));
        long transactionsBefore = transactionCount();
        long entriesBefore = database.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
        long outboxBefore = database.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class);
        assertThat(record(unbalanced).body()).contains("UNBALANCED_TRANSACTION");
        assertThat(record(transfer(merchant42, payouts, "50.00")).body()).contains("OVERDRAFT_EXCEEDED");
        assertThat(transactionCount()).isEqualTo(transactionsBefore);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class)).isEqualTo(entriesBefore);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isEqualTo(outboxBefore);
        assertThat(accounts.get(merchant42).balance()).isEqualByComparingTo("25.50");

        String reverseKey = key();
        var reversed = idempotency.execute("reverse-transaction", reverseKey, original.id(), () -> ledger.reverse(original.id()));
        assertThat(reversed.status()).isEqualTo(201);
        assertThat(second.getBean(com.ledger.application.IdempotencyService.class)
                .execute("reverse-transaction", reverseKey, original.id(), () -> { throw new AssertionError("Must replay"); }))
                .isEqualTo(reversed);
        assertThat(accounts.get(merchant42).balance()).isEqualByComparingTo("125.50");
        assertThat(accounts.get(payouts).balance()).isEqualByComparingTo("0.00");
        assertThat(accounts.get(fees).balance()).isEqualByComparingTo("0.00");
        assertThat(ledger.get(original.id())).isEqualTo(original);
        assertThat(idempotency.execute("reverse-transaction", key(), original.id(), () -> ledger.reverse(original.id())).status()).isEqualTo(409);
        assertIntegrity();
    }

    @Test
    void yen15000SettlementAndDocumentPrecisionCurrencyAndMissingAccountRefusals() {
        String merchant7 = account("JPY");
        String payoutJpy = account("JPY");
        String merchant42 = account("EUR");
        BigDecimal settlementBefore = accounts.get("settlement-JPY").balance();
        payments.settle(new PaymentSettled(key(), key(), merchant7, new BigDecimal("15000"), "JPY"));
        assertThat(accounts.get(merchant7).balance()).isEqualByComparingTo("15000");
        assertThat(accounts.get("settlement-JPY").balance().subtract(settlementBefore)).isEqualByComparingTo("-15000");
        long before = transactionCount();
        assertThat(record(transfer(merchant7, payoutJpy, "100.5")).body()).contains("INVALID_PRECISION");
        assertThat(record(transfer(merchant42, merchant7, "10")).body()).contains("MIXED_CURRENCIES");
        assertThat(record(transfer("unknown-" + key(), payoutJpy, "100")).body()).contains("UNKNOWN_ACCOUNT");
        assertThat(transactionCount()).isEqualTo(before);
        assertIntegrity();
    }

    private EntryInput entry(String account, Direction direction, String amount) {
        return new EntryInput(account, direction, new BigDecimal(amount));
    }
}
