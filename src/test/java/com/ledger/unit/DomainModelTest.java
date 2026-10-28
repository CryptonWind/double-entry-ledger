package com.ledger.unit;

import com.ledger.domain.model.Account;
import com.ledger.domain.exception.LedgerException;
import com.ledger.domain.model.Direction;
import com.ledger.domain.model.LedgerTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** The business model executes without JPA entities, repositories, Spring proxies or messaging. */
class DomainModelTest {
    @Test
    void documentFlowRunsEntirelyInDomainObjects() {
        Instant now = Instant.parse("2026-10-28T12:00:00Z");
        var settlement = Account.restore("settlement", "EUR", null, BigDecimal.ZERO, now, null);
        var merchant = Account.open("merchant-42", "EUR", null, now);
        var payouts = Account.open("payouts", "EUR", null, now);
        var fees = Account.open("fees", "EUR", null, now);
        LedgerTransaction.record("settled", "Settlement", List.of(post("settlement", Direction.DEBIT, "125.50"),
                post("merchant-42", Direction.CREDIT, "125.50")), Map.of("settlement", settlement, "merchant-42", merchant), null, now);
        Map<String, Account> affected = new LinkedHashMap<>();
        affected.put(merchant.getId(), merchant);
        affected.put(payouts.getId(), payouts);
        affected.put(fees.getId(), fees);
        var payout = LedgerTransaction.record("payout", "Payout with fee", List.of(
                post(merchant.getId(), Direction.DEBIT, "100.00"), post(payouts.getId(), Direction.CREDIT, "98.50"),
                post(fees.getId(), Direction.CREDIT, "1.50")), affected, null, now);
        assertThat(merchant.getBalance()).isEqualByComparingTo("25.50");
        assertThat(payouts.getBalance()).isEqualByComparingTo("98.50");
        assertThat(fees.getBalance()).isEqualByComparingTo("1.50");
        assertThatThrownBy(() -> LedgerTransaction.record("refused", "Overdraft", List.of(
                post(merchant.getId(), Direction.DEBIT, "50.00"), post(payouts.getId(), Direction.CREDIT, "50.00")),
                Map.of(merchant.getId(), merchant, payouts.getId(), payouts), null, now)).isInstanceOf(LedgerException.class);
        assertThat(merchant.getBalance()).isEqualByComparingTo("25.50");
        var reversal = LedgerTransaction.record("reversal", "Reversal", payout.reversalPostings(), affected, payout.id(), now);
        assertThat(merchant.getBalance()).isEqualByComparingTo("125.50");
        assertThat(payouts.getBalance()).isEqualByComparingTo("0.00");
        assertThat(fees.getBalance()).isEqualByComparingTo("0.00");
        assertThatThrownBy(reversal::reversalPostings).isInstanceOf(LedgerException.class);
        assertThat(payout.entries()).hasSize(3);
    }

    private LedgerTransaction.Posting post(String id, Direction direction, String value) {
        return new LedgerTransaction.Posting(id, direction, new BigDecimal(value));
    }

    @Test
    void unrelatedAccountsAreNotValidatedOrMutatedAndMissingAccountsLeaveStateIntact() {
        var source = Account.restore("source", "EUR", null, BigDecimal.ZERO, Instant.EPOCH, null);
        var target = Account.open("target", "EUR", null, Instant.EPOCH);
        var unrelated = Account.open("unrelated", "JPY", null, Instant.EPOCH);
        var postings = List.of(post("source", Direction.DEBIT, "125.50"), post("target", Direction.CREDIT, "125.50"));
        LedgerTransaction.record("tx", "Settlement", postings, Map.of("source", source, "target", target, "unrelated", unrelated), null, Instant.now());
        assertThat(unrelated.getBalance()).isEqualByComparingTo("0");
        assertThat(unrelated.getLastPostedAt()).isNull();
        assertThatThrownBy(() -> LedgerTransaction.record("missing", "Missing", postings, Map.of("source", source), null, Instant.now()))
                .isInstanceOf(LedgerException.class);
        assertThat(source.getBalance()).isEqualByComparingTo("-125.50");
    }

    @Test
    void invalidRestoredStateCannotBypassAccountInvariants() {
        assertThatThrownBy(() -> Account.restore("id", null, null, BigDecimal.ZERO, Instant.EPOCH, null)).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> Account.restore("id", "JPY", null, new BigDecimal("1.5"), Instant.EPOCH, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.restore("id", "EUR", BigDecimal.ZERO, new BigDecimal("-1"), Instant.EPOCH, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.open("", "EUR", null, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }
}
