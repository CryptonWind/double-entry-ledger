package com.ledger.unit;

import com.ledger.application.port.out.AccountStore;
import com.ledger.domain.model.Account;
import com.ledger.application.LedgerService;
import com.ledger.application.model.LedgerModels.*;
import com.ledger.application.port.out.TransactionAnnouncements;
import com.ledger.application.port.out.TransactionStore;
import com.ledger.domain.model.Direction;
import com.ledger.domain.model.LedgerTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerUseCasePortsTest {
    @Test
    void useCaseCoordinatesPortsWithoutKnowingHibernateOrRabbitMq() {
        AccountStore accounts = mock(AccountStore.class);
        TransactionStore transactions = mock(TransactionStore.class);
        TransactionAnnouncements announcements = mock(TransactionAnnouncements.class);
        var useCase = new LedgerService(accounts, transactions, announcements, new com.ledger.application.port.out.UnitOfWork() {
            @Override public <T> T execute(java.util.function.Supplier<T> action) { return action.get(); }
        });
        var settlement = new Account("settlement-EUR", "EUR", null, BigDecimal.ZERO, Instant.EPOCH, null);
        var merchant = Account.open("merchant-42", "EUR", null, Instant.EPOCH);
        when(accounts.lock(settlement.getId())).thenReturn(Optional.of(settlement));
        when(accounts.lock(merchant.getId())).thenReturn(Optional.of(merchant));
        var result = useCase.record(new RecordTransaction("Settled payment", List.of(
                new EntryInput(settlement.getId(), Direction.DEBIT, new BigDecimal("125.50")),
                new EntryInput(merchant.getId(), Direction.CREDIT, new BigDecimal("125.50")))));
        var transaction = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(transactions).append(transaction.capture());
        verify(accounts).updateLocked(merchant);
        verify(accounts).updateLocked(settlement);
        verify(announcements).enqueue(transaction.getValue());
        assertThat(transaction.getValue().id()).isEqualTo(result.id());
        assertThat(merchant.getBalance()).isEqualByComparingTo("125.50");
        assertThat(settlement.getBalance()).isEqualByComparingTo("-125.50");
        var orderedLocks = inOrder(accounts);
        orderedLocks.verify(accounts).lock("merchant-42");
        orderedLocks.verify(accounts).lock("settlement-EUR");
    }
}
