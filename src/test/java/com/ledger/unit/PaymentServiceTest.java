package com.ledger.unit;

import com.ledger.infrastructure.output.persistence.AccountEntity;
import com.ledger.infrastructure.output.persistence.ProcessedPayment;
import com.ledger.application.PaymentService.PaymentSettled;
import com.ledger.domain.exception.LedgerException;
import com.ledger.support.UnitTestBase;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentServiceTest extends UnitTestBase {
    @Test
    void documentPayment12550DebitsSettlementAndCreditsMerchant42() {
        var stored = prepare("EUR", "merchant-42");
        var settlement = new AccountEntity("settlement-EUR", "EUR", null);
        var merchant = new AccountEntity("merchant-42", "EUR", BigDecimal.ZERO);
        when(accountRepository.lockById(settlement.getId())).thenReturn(Optional.of(settlement));
        when(accountRepository.lockById(merchant.getId())).thenReturn(Optional.of(merchant));
        when(accountRepository.findById(settlement.getId())).thenReturn(Optional.of(settlement));
        when(accountRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
        String transactionId = payments.settle(payment("event-1", "125.50", "EUR", "merchant-42"));
        assertThat(settlement.getBalance()).isEqualByComparingTo("-125.50");
        assertThat(merchant.getBalance()).isEqualByComparingTo("125.50");
        verify(stored).complete(transactionId);
    }

    @Test
    void newEventIdForAlreadyProcessedPaymentDoesNotPostAgain() {
        var stored = prepare("JPY", "merchant-7");
        when(stored.getTransactionId()).thenReturn("original-transaction");
        assertThat(payments.settle(payment("new-event", "15000", "JPY", "merchant-7"))).isEqualTo("original-transaction");
        verify(accountRepository, never()).lockById(anyString());
        verify(entities, never()).persist(any());
    }

    @Test
    void documentYen15000SettlementPostsIntegerAmounts() {
        prepare("JPY", "merchant-7");
        var settlement = new AccountEntity("settlement-JPY", "JPY", null);
        var merchant = new AccountEntity("merchant-7", "JPY", BigDecimal.ZERO);
        when(accountRepository.lockById(settlement.getId())).thenReturn(Optional.of(settlement));
        when(accountRepository.lockById(merchant.getId())).thenReturn(Optional.of(merchant));
        when(accountRepository.findById(settlement.getId())).thenReturn(Optional.of(settlement));
        when(accountRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
        payments.settle(payment("event-7", "15000", "JPY", "merchant-7"));
        assertThat(settlement.getBalance()).isEqualByComparingTo("-15000");
        assertThat(merchant.getBalance()).isEqualByComparingTo("15000");
    }

    @Test
    void rejectsConflictingPaymentUnknownMerchantAndWrongCurrency() {
        var stored = prepare("EUR", "merchant-42");
        when(stored.getFingerprint()).thenReturn("different");
        error(() -> payments.settle(payment("event", "125.50", "EUR", "merchant-42")), "PAYMENT_CONFLICT");
        when(stored.getFingerprint()).thenAnswer(invocation -> fingerprintFromInsert());
        @SuppressWarnings("unchecked") var query = (TypedQuery<String>) entities.createQuery(
                "select a.currency from AccountEntity a where a.id = :id", String.class);
        when(query.getResultList()).thenReturn(List.of());
        error(() -> payments.settle(payment("event", "125.50", "EUR", "merchant-42")), "UNKNOWN_ACCOUNT");
        when(query.getResultList()).thenReturn(List.of("JPY"));
        error(() -> payments.settle(payment("event", "125.50", "EUR", "merchant-42")), "WRONG_CURRENCY");
        verify(entities, never()).persist(any());
    }

    @Test
    void malformedIdentityAndFractionalYenAreRejectedBeforeReservation() {
        error(() -> payments.settle(payment("", "125.50", "EUR", "merchant-42")), "INVALID_PAYMENT");
        error(() -> payments.settle(payment("event", "100.5", "JPY", "merchant-7")), "INVALID_PRECISION");
        verifyNoInteractions(database);
    }

    private String fingerprint;
    private String fingerprintFromInsert() { return fingerprint; }
    private ProcessedPayment prepare(String currency, String merchant) {
        var stored = mock(ProcessedPayment.class);
        when(database.update(anyString(), any(), any())).thenAnswer(invocation -> {
            fingerprint = invocation.getArgument(2);
            return 1;
        });
        when(stored.getFingerprint()).thenAnswer(invocation -> fingerprint);
        when(entities.find(ProcessedPayment.class, "payment-42", LockModeType.PESSIMISTIC_WRITE)).thenReturn(stored);
        @SuppressWarnings("unchecked") TypedQuery<String> query = mock(TypedQuery.class, RETURNS_SELF);
        when(query.getResultList()).thenReturn(List.of(currency));
        when(entities.createQuery(anyString(), eq(String.class))).thenReturn(query);
        return stored;
    }
    private PaymentSettled payment(String event, String amount, String currency, String merchant) {
        return new PaymentSettled(event, "payment-42", merchant, new BigDecimal(amount), currency);
    }
    private void error(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LedgerException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
