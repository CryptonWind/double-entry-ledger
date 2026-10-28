package com.ledger.unit;

import com.ledger.infrastructure.output.persistence.AccountEntity;
import com.ledger.infrastructure.output.persistence.OutboxEvent;
import com.ledger.domain.exception.LedgerException;
import com.ledger.support.UnitTestBase;
import com.ledger.infrastructure.output.persistence.LedgerEntryEntity;
import com.ledger.infrastructure.output.persistence.LedgerTransactionEntity;
import com.ledger.application.model.LedgerModels.*;
import com.ledger.domain.model.Direction;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerServiceTest extends UnitTestBase {
    private AccountEntity settlement;
    private AccountEntity merchant;
    private AccountEntity payout;
    private AccountEntity fees;

    @BeforeEach
    void documentAccounts() {
        @SuppressWarnings("unchecked") jakarta.persistence.TypedQuery<String> currencies = mock(jakarta.persistence.TypedQuery.class);
        when(entities.createQuery(anyString(), eq(String.class))).thenReturn(currencies);
        when(currencies.setParameter(eq("id"), any())).thenReturn(currencies);
        when(currencies.getResultList()).thenReturn(List.of("EUR"));
        settlement = account("settlement-EUR", "EUR", null, "0.00");
        merchant = account("merchant-42", "EUR", BigDecimal.ZERO, "0.00");
        payout = account("payouts-EUR", "EUR", BigDecimal.ZERO, "0.00");
        fees = account("fees-EUR", "EUR", BigDecimal.ZERO, "0.00");
    }

    @Test
    void documentSettlementPayoutFeeAndReversalPreserveExactBalances() {
        var payment = ledger.record(transfer(settlement.getId(), merchant.getId(), "125.50"));
        assertThat(settlement.getBalance()).isEqualByComparingTo("-125.50");
        assertThat(merchant.getBalance()).isEqualByComparingTo("125.50");
        var original = ledger.record(payout());
        assertThat(merchant.getBalance()).isEqualByComparingTo("25.50");
        assertThat(payout.getBalance()).isEqualByComparingTo("98.50");
        assertThat(fees.getBalance()).isEqualByComparingTo("1.50");
        assertZeroTotal();

        var originalEntity = new LedgerTransactionEntity(original.id(), original.description(), original.recordedAt(), null);
        when(entities.find(LedgerTransactionEntity.class, original.id(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(originalEntity);
        when(entryRepository.findByTransactionIdOrderByPosition(original.id())).thenReturn(List.of(
                storedEntry(original.id(), 0, merchant.getId(), "DEBIT", "100.00"),
                storedEntry(original.id(), 1, payout.getId(), "CREDIT", "98.50"),
                storedEntry(original.id(), 2, fees.getId(), "CREDIT", "1.50")));
        var reversal = ledger.reverse(original.id());
        assertThat(reversal.reversesTransactionId()).isEqualTo(original.id());
        assertThat(reversal.entries()).extracting(EntryView::direction)
                .containsExactly(Direction.CREDIT, Direction.DEBIT, Direction.DEBIT);
        assertThat(merchant.getBalance()).isEqualByComparingTo("125.50");
        assertThat(payout.getBalance()).isEqualByComparingTo("0.00");
        assertThat(fees.getBalance()).isEqualByComparingTo("0.00");
        assertThat(originalEntity.getDescription()).isEqualTo("Payout with fee");
        assertZeroTotal();
        var capture = ArgumentCaptor.forClass(Object.class);
        verify(entities, atLeastOnce()).persist(capture.capture());
        assertThat(capture.getAllValues().stream().filter(OutboxEvent.class::isInstance)).hasSize(3);
        assertThat(payment.currency()).isEqualTo("EUR");
    }

    @Test
    void documentYenSettlementRecordsFifteenThousand() {
        var settlementJpy = account("settlement-JPY", "JPY", null, "0");
        var merchant7 = account("merchant-7", "JPY", BigDecimal.ZERO, "0");
        var result = ledger.record(transfer(settlementJpy.getId(), merchant7.getId(), "15000"));
        assertThat(settlementJpy.getBalance()).isEqualByComparingTo("-15000");
        assertThat(merchant7.getBalance()).isEqualByComparingTo("15000");
        assertThat(result.entries().get(0).amount()).isEqualTo(new BigDecimal("15000"));
    }

    @Test
    void documentUnbalanced100Debit99CreditIsRefusedWithoutWrites() {
        fundAfterDocumentPayout();
        rejected(new RecordTransaction("Unbalanced", List.of(entry(merchant.getId(), Direction.DEBIT, "100.00"),
                entry(payout.getId(), Direction.CREDIT, "99.00"))), "UNBALANCED_TRANSACTION");
    }

    @Test
    void documentFiftyDebitWouldExceedZeroOverdraftFrom2550() {
        fundAfterDocumentPayout();
        rejected(transfer(merchant.getId(), payout.getId(), "50.00"), "OVERDRAFT_EXCEEDED");
        assertThat(merchant.getBalance()).isEqualByComparingTo("25.50");
        assertThat(merchant.getBalance().subtract(new BigDecimal("50.00"))).isEqualByComparingTo("-24.50");
    }

    @Test
    void documentFractionalYenMixedCurrenciesSingleEntryAndUnknownAccountAreRefused() {
        var merchant7 = account("merchant-7", "JPY", BigDecimal.ZERO, "15000");
        account("payouts-JPY", "JPY", BigDecimal.ZERO, "0");
        rejected(transfer(merchant7.getId(), "payouts-JPY", "100.5"), "INVALID_PRECISION");
        rejected(transfer(merchant.getId(), merchant7.getId(), "10"), "MIXED_CURRENCIES");
        rejected(new RecordTransaction("One entry", List.of(entry(merchant.getId(), Direction.CREDIT, "10.00"))), "INVALID_ENTRY_COUNT");
        when(accountRepository.lockById("unknown")).thenReturn(Optional.empty());
        rejected(transfer("unknown", merchant.getId(), "10.00"), "UNKNOWN_ACCOUNT");
    }

    @Test
    void locksAccountsInStableIdOrderAndUsesNetEffectForRepeatedAccount() {
        ledger.record(new RecordTransaction("Net zero", List.of(
                entry(merchant.getId(), Direction.DEBIT, "10.00"), entry(merchant.getId(), Direction.CREDIT, "10.00"))));
        assertThat(merchant.getBalance()).isEqualByComparingTo("0");
        clearInvocations(accountRepository);
        ledger.record(transfer(settlement.getId(), merchant.getId(), "1.00"));
        var order = inOrder(accountRepository);
        order.verify(accountRepository).lockById("merchant-42");
        order.verify(accountRepository).lockById("settlement-EUR");
    }

    @Test
    void rejectsSecondReversalReversalOfReversalAndMissingOriginal() {
        String id = "original";
        when(entities.find(LedgerTransactionEntity.class, id, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(new LedgerTransactionEntity(id, "Original", Instant.now(), null));
        when(transactionRepository.existsByReversesTransactionId(id)).thenReturn(true);
        when(entryRepository.findByTransactionIdOrderByPosition(id)).thenReturn(List.of(
                storedEntry(id, 0, settlement.getId(), "DEBIT", "125.50"),
                storedEntry(id, 1, merchant.getId(), "CREDIT", "125.50")));
        assertError(() -> ledger.reverse(id), "ALREADY_REVERSED");
        when(entities.find(LedgerTransactionEntity.class, "reversal", LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(new LedgerTransactionEntity("reversal", "Reversal", Instant.now(), id));
        when(entryRepository.findByTransactionIdOrderByPosition("reversal")).thenReturn(List.of(
                storedEntry("reversal", 0, settlement.getId(), "CREDIT", "125.50"),
                storedEntry("reversal", 1, merchant.getId(), "DEBIT", "125.50")));
        assertError(() -> ledger.reverse("reversal"), "REVERSAL_OF_REVERSAL");
        assertError(() -> ledger.reverse("missing"), "TRANSACTION_NOT_FOUND");
        verify(entities, never()).persist(any());
    }

    @Test
    void timestampRemainsMonotonicWhenAccountPostingTimeIsAheadOfClock() {
        Instant future = Instant.now().plusSeconds(60);
        merchant.postBalance(BigDecimal.ZERO, future);
        var result = ledger.record(transfer(settlement.getId(), merchant.getId(), "0.10"));
        assertThat(result.recordedAt()).isAfter(future);
        assertThat(merchant.getLastPostedAt()).isEqualTo(result.recordedAt());
    }

    @Test
    void reversalCannotDebitPayoutFundsThatHaveAlreadyBeenSpent() {
        fundAfterDocumentPayout();
        payout.postBalance(BigDecimal.ZERO, Instant.now());
        when(entities.find(LedgerTransactionEntity.class, "original", LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(new LedgerTransactionEntity("original", "Payout", Instant.now(), null));
        when(entryRepository.findByTransactionIdOrderByPosition("original")).thenReturn(List.of(
                storedEntry("original", 0, merchant.getId(), "DEBIT", "100.00"),
                storedEntry("original", 1, payout.getId(), "CREDIT", "98.50"),
                storedEntry("original", 2, fees.getId(), "CREDIT", "1.50")));
        assertError(() -> ledger.reverse("original"), "OVERDRAFT_EXCEEDED");
        assertThat(merchant.getBalance()).isEqualByComparingTo("25.50");
        verify(entities, never()).persist(any());
    }

    @Test
    void rejectsZeroNegativeMissingAmountAndInvalidDescriptionBeforeFinancialWrites() {
        rejected(transfer(settlement.getId(), merchant.getId(), "0.00"), "INVALID_AMOUNT");
        rejected(transfer(settlement.getId(), merchant.getId(), "-1.00"), "INVALID_AMOUNT");
        rejected(new RecordTransaction("Missing amount", List.of(new EntryInput(settlement.getId(), Direction.DEBIT, null),
                entry(merchant.getId(), Direction.CREDIT, "1.00"))), "INVALID_AMOUNT");
        rejected(new RecordTransaction(" ", List.of(entry(settlement.getId(), Direction.DEBIT, "1.00"),
                entry(merchant.getId(), Direction.CREDIT, "1.00"))), "INVALID_DESCRIPTION");
    }

    @Test
    void readsBackImmutableTransactionAndItsEntries() {
        Instant at = Instant.parse("2026-10-28T12:00:00Z");
        when(transactionRepository.findById("tx")).thenReturn(Optional.of(new LedgerTransactionEntity("tx", "Settlement", at, null)));
        when(entryRepository.findByTransactionIdOrderByPosition("tx")).thenReturn(List.of(
                storedEntry("tx", 0, settlement.getId(), "DEBIT", "125.50"),
                storedEntry("tx", 1, merchant.getId(), "CREDIT", "125.50")));
        var result = ledger.get("tx");
        assertThat(result.description()).isEqualTo("Settlement");
        assertThat(result.recordedAt()).isEqualTo(at);
        assertThat(result.entries()).hasSize(2);
        assertError(() -> ledger.get("missing"), "TRANSACTION_NOT_FOUND");
    }

    private AccountEntity account(String id, String currency, BigDecimal limit, String balance) {
        var account = new AccountEntity(id, currency, limit);
        account.postBalance(new BigDecimal(balance), Instant.parse("2026-01-01T00:00:00Z"));
        when(accountRepository.lockById(id)).thenReturn(Optional.of(account));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        return account;
    }

    private void fundAfterDocumentPayout() {
        settlement.postBalance(new BigDecimal("-125.50"), Instant.now());
        merchant.postBalance(new BigDecimal("25.50"), Instant.now());
        payout.postBalance(new BigDecimal("98.50"), Instant.now());
        fees.postBalance(new BigDecimal("1.50"), Instant.now());
    }

    private RecordTransaction payout() {
        return new RecordTransaction("Payout with fee", List.of(entry(merchant.getId(), Direction.DEBIT, "100.00"),
                entry(payout.getId(), Direction.CREDIT, "98.50"), entry(fees.getId(), Direction.CREDIT, "1.50")));
    }
    private RecordTransaction transfer(String from, String to, String amount) {
        return new RecordTransaction("Transfer", List.of(entry(from, Direction.DEBIT, amount), entry(to, Direction.CREDIT, amount)));
    }
    private EntryInput entry(String id, Direction direction, String amount) { return new EntryInput(id, direction, new BigDecimal(amount)); }
    private LedgerEntryEntity storedEntry(String id, int position, String account, String direction, String amount) {
        return new LedgerEntryEntity("e-" + position, id, position, account, direction, new BigDecimal(amount), BigDecimal.ZERO);
    }
    private void rejected(RecordTransaction request, String code) {
        assertError(() -> ledger.record(request), code);
        verify(entities, never()).persist(any());
    }
    private void assertError(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LedgerException.class, error -> assertThat(error.getCode()).isEqualTo(code));
    }
    private void assertZeroTotal() {
        assertThat(settlement.getBalance().add(merchant.getBalance()).add(payout.getBalance()).add(fees.getBalance()))
                .isEqualByComparingTo("0");
    }
}
