package com.ledger.unit;

import com.ledger.application.*;
import com.ledger.application.model.LedgerModels.*;
import com.ledger.application.port.in.TransactionUseCases;
import com.ledger.application.port.out.*;
import com.ledger.domain.exception.LedgerException;
import com.ledger.domain.model.Account;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Core use cases tested exclusively through ports: no Spring, JPA, RabbitMQ or JSON implementation. */
class IsolatedUseCasesTest {
    private final UnitOfWork work = new UnitOfWork() {
        @Override public <T> T execute(Supplier<T> action) { return action.get(); }
    };

    @Test
    void accountOpeningAndHistoryUseOnlyBusinessModelsAndPorts() {
        var store = mock(AccountStore.class);
        var service = new AccountService(store, work);
        var opened = service.open(new OpenAccount("EUR", null));
        var captured = ArgumentCaptor.forClass(Account.class);
        verify(store).insert(captured.capture());
        assertThat(captured.getValue().getBalance()).isEqualByComparingTo("0.00");
        when(store.find(opened.id())).thenReturn(Optional.of(captured.getValue()));
        var reader = mock(AccountHistoryReader.class);
        var history = new AccountHistoryService(service, reader, work);
        Instant at = Instant.parse("2026-10-28T12:00:00Z");
        when(reader.balance(opened.id(), at)).thenReturn(new BigDecimal("25.50"));
        assertThat(history.balance(opened.id(), at).balance()).isEqualByComparingTo("25.50");
        when(reader.entries(opened.id(), 0, 50, at, 2))
                .thenReturn(new AccountHistoryService.EntryPage(List.of(), 0, 50, 0, 0, at));
        assertThat(history.entries(opened.id(), 0, 50, at).content()).isEmpty();
    }

    @Test
    void paymentBuildsTheDocumentSettlementAndDeduplicatesThroughPorts() {
        var store = mock(PaymentStore.class);
        var accounts = mock(AccountStore.class);
        var codec = mock(JsonCodec.class);
        var ledger = mock(TransactionUseCases.class);
        var service = new PaymentService(work, store, accounts, codec, ledger);
        when(codec.fingerprint(any())).thenReturn("fingerprint");
        when(store.reserve("pay-42", "fingerprint")).thenReturn(new PaymentStore.Reservation("fingerprint", null));
        when(accounts.currency("merchant-42")).thenReturn(Optional.of("EUR"));
        when(ledger.record(any())).thenReturn(new TransactionView("tx", "Settlement", Instant.EPOCH, null, "EUR", List.of()));
        var payment = new PaymentService.PaymentSettled("evt-1", "pay-42", "merchant-42", new BigDecimal("125.50"), "EUR");
        assertThat(service.settle(payment)).isEqualTo("tx");
        var request = ArgumentCaptor.forClass(RecordTransaction.class);
        verify(ledger).record(request.capture());
        assertThat(request.getValue().entries()).extracting(EntryInput::accountId).containsExactly("settlement-EUR", "merchant-42");
        assertThat(request.getValue().entries()).allSatisfy(entry -> assertThat(entry.amount()).isEqualByComparingTo("125.50"));
        verify(store).complete("pay-42", "tx");
        clearInvocations(ledger);
        when(store.reserve("pay-42", "fingerprint")).thenReturn(new PaymentStore.Reservation("fingerprint", "tx"));
        assertThat(service.settle(new PaymentService.PaymentSettled("evt-2", "pay-42", "merchant-42", new BigDecimal("125.50"), "EUR")))
                .isEqualTo("tx");
        verifyNoInteractions(ledger);
    }

    @Test
    void idempotencyReplaysRefusalsAndDetectsDifferentContentWithoutAnyPersistenceImplementation() {
        var store = mock(IdempotencyStore.class);
        var codec = mock(JsonCodec.class);
        var service = new IdempotencyService(store, codec, work);
        when(codec.fingerprint("request")).thenReturn("fingerprint");
        when(store.reserve("record", "key", "fingerprint"))
                .thenReturn(new IdempotencyStore.Reservation("fingerprint", 422, "original-error"));
        assertThat(service.execute("record", "key", "request", () -> { throw new AssertionError("Must replay"); }).body())
                .isEqualTo("original-error");
        when(store.reserve("record", "key", "fingerprint"))
                .thenReturn(new IdempotencyStore.Reservation("different", 201, "other"));
        assertThatThrownBy(() -> service.execute("record", "key", "request", () -> null))
                .isInstanceOfSatisfying(LedgerException.class, error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
}
