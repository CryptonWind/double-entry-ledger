package com.ledger.unit;

import com.ledger.infrastructure.output.persistence.AccountEntity;
import com.ledger.domain.exception.LedgerException;
import com.ledger.support.UnitTestBase;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountHistoryServiceTest extends UnitTestBase {
    @Test
    void mapsDocumentPayoutHistoryToPagedDecimalBalances() {
        when(accountRepository.findById("merchant-42")).thenReturn(Optional.of(new AccountEntity("merchant-42", "EUR", BigDecimal.ZERO)));
        @SuppressWarnings("unchecked") TypedQuery<Long> count = mock(TypedQuery.class, RETURNS_SELF);
        @SuppressWarnings("unchecked") TypedQuery<Object[]> rows = mock(TypedQuery.class, RETURNS_SELF);
        when(entities.createQuery(anyString(), eq(Long.class))).thenReturn(count);
        when(entities.createQuery(anyString(), eq(Object[].class))).thenReturn(rows);
        when(count.getSingleResult()).thenReturn(2L);
        Instant at = Instant.parse("2026-10-28T12:00:00Z");
        when(rows.getResultList()).thenReturn(java.util.Collections.singletonList(new Object[]{
                "entry", "payout", at, 0, "DEBIT", new BigDecimal("100.00"), new BigDecimal("25.50")}));
        var page = history.entries("merchant-42", 0, 1, at);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).singleElement().satisfies(entry -> {
            assertThat(entry.amount()).isEqualByComparingTo("100.00");
            assertThat(entry.balanceAfter()).isEqualByComparingTo("25.50");
        });
        verify(rows).setMaxResults(1);
        verify(rows).setParameter("at", at);
    }

    @Test
    void emptyHistoricalSumReturnsZeroAndInvalidParametersAreRejected() {
        when(accountRepository.findById("merchant-7")).thenReturn(Optional.of(new AccountEntity("merchant-7", "JPY", BigDecimal.ZERO)));
        @SuppressWarnings("unchecked") TypedQuery<BigDecimal> sum = mock(TypedQuery.class, RETURNS_SELF);
        when(entities.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(sum);
        assertThat(history.balance("merchant-7", Instant.EPOCH).balance()).isEqualTo(BigDecimal.ZERO);
        when(sum.getSingleResult()).thenReturn(new BigDecimal("15000.00"));
        assertThat(history.balance("merchant-7", Instant.now()).balance()).isEqualTo(new BigDecimal("15000"));
        assertThatThrownBy(() -> history.balance("merchant-7", null)).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> history.entries("merchant-7", -1, 50, null)).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> history.entries("merchant-7", 0, 201, null)).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> history.entries("merchant-7", Integer.MAX_VALUE, 200, null)).isInstanceOf(LedgerException.class);
    }
}
