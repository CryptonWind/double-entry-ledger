package com.ledger.unit;

import com.ledger.infrastructure.output.persistence.AccountEntity;
import com.ledger.domain.exception.LedgerException;
import com.ledger.support.UnitTestBase;
import com.ledger.application.model.LedgerModels.OpenAccount;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountServiceTest extends UnitTestBase {
    @Test
    void opensZeroBalanceEurAccountWithDefaultZeroOverdraft() {
        var result = accounts.open(new OpenAccount("EUR", null));
        assertThat(result.balance()).isEqualByComparingTo("0.00");
        assertThat(result.overdraftLimit()).isEqualByComparingTo("0.00");
        assertThat(result.unlimitedOverdraft()).isFalse();
        var captured = ArgumentCaptor.forClass(AccountEntity.class);
        verify(entities).persist(captured.capture());
        assertThat(captured.getValue().getId()).isEqualTo(result.id());
    }

    @Test
    void opensConfiguredOverdraftAndReadsCurrentBalance() {
        var opened = accounts.open(new OpenAccount("EUR", new BigDecimal("50.00")));
        assertThat(opened.overdraftLimit()).isEqualByComparingTo("50.00");
        var merchant = new AccountEntity("merchant-42", "EUR", BigDecimal.ZERO);
        merchant.postBalance(new BigDecimal("125.50"), java.time.Instant.now());
        when(accountRepository.findById("merchant-42")).thenReturn(Optional.of(merchant));
        assertThat(accounts.get("merchant-42").balance()).isEqualByComparingTo("125.50");
    }

    @Test
    void readsSettlementAccountWithUnlimitedOverdraft() {
        var settlement = new AccountEntity("settlement-JPY", "JPY", null);
        settlement.postBalance(new BigDecimal("-15000"), java.time.Instant.now());
        when(accountRepository.findById(settlement.getId())).thenReturn(Optional.of(settlement));
        var result = accounts.get(settlement.getId());
        assertThat(result.unlimitedOverdraft()).isTrue();
        assertThat(result.overdraftLimit()).isNull();
        assertThat(result.balance()).isEqualTo(new BigDecimal("-15000"));
    }

    @Test
    void unknownAccountReturnsNotFound() {
        when(accountRepository.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> accounts.get("missing")).isInstanceOfSatisfying(LedgerException.class,
                error -> assertThat(error.getStatus()).isEqualTo(404));
    }

    @Test
    void refusesUnsupportedCurrencyAndNegativeOverdraftBeforePersisting() {
        assertThatThrownBy(() -> accounts.open(new OpenAccount("GBP", null))).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> accounts.open(new OpenAccount("EUR", new BigDecimal("-1.00"))))
                .isInstanceOf(LedgerException.class);
        verify(entities, never()).persist(any());
    }
}
