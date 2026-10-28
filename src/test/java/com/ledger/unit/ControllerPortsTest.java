package com.ledger.unit;

import com.ledger.application.model.LedgerModels.*;
import com.ledger.application.port.in.*;
import com.ledger.domain.exception.LedgerException;
import com.ledger.infrastructure.input.rest.AccountController;
import com.ledger.infrastructure.input.rest.TransactionController;
import com.ledger.infrastructure.input.rest.TransactionRequests;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Input adapters are tested without constructing services, entities or repositories. */
class ControllerPortsTest {
    @Test
    void accountControllerMapsTransportToCommandAndDelegatesReadsToPorts() {
        var accounts = mock(AccountUseCases.class);
        var history = mock(AccountHistoryUseCases.class);
        var idempotency = mock(IdempotentOperations.class);
        var controller = new AccountController(accounts, idempotency, history);
        when(idempotency.execute(eq("open-account"), eq("key"), eq(new OpenAccount("EUR", BigDecimal.ZERO)), any()))
                .thenReturn(new IdempotentOperations.StoredResponse(201, "{\"id\":\"account\"}"));
        var result = controller.open("key", new AccountController.OpenAccountRequest("EUR", BigDecimal.ZERO));
        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(result.getBody()).contains("account");
        var view = new AccountView("account", "EUR", BigDecimal.ZERO, false, BigDecimal.ZERO);
        when(accounts.get("account")).thenReturn(view);
        assertThat(controller.get("account")).isEqualTo(view);
        Instant at = Instant.EPOCH;
        controller.entries("account", 0, 50, at);
        controller.balance("account", at);
        verify(history).entries("account", 0, 50, at);
        verify(history).balance("account", at);
    }

    @Test
    void transactionControllerUsesPortsForRecordingReadingAndReversing() {
        var ledger = mock(TransactionUseCases.class);
        var idempotency = mock(IdempotentOperations.class);
        var controller = new TransactionController(ledger, idempotency);
        when(idempotency.execute(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(3);
            operation.get();
            return new IdempotentOperations.StoredResponse(201, "{}");
        });
        var request = new TransactionRequests.Record("Settlement", List.of(
                new TransactionRequests.Entry("settlement-EUR", com.ledger.domain.model.Direction.DEBIT, new BigDecimal("125.50")),
                new TransactionRequests.Entry("merchant", com.ledger.domain.model.Direction.CREDIT, new BigDecimal("125.50"))));
        assertThat(controller.record("key", request).getStatusCode().value()).isEqualTo(201);
        verify(ledger).record(argThat(command -> command.description().equals("Settlement") && command.entries().size() == 2));
        controller.get("tx");
        verify(ledger).get("tx");
        controller.reverse("tx", "reverse-key", null);
        verify(ledger).reverse("tx");
        assertThatThrownBy(() -> controller.reverse("tx", "key", new byte[]{1})).isInstanceOf(LedgerException.class);
    }
}
