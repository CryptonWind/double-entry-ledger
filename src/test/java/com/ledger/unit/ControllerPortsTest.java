package com.ledger.unit;

import com.ledger.application.model.LedgerModels.*;
import com.ledger.application.port.in.*;
import com.ledger.domain.exception.LedgerException;
import com.ledger.infrastructure.input.rest.AccountController;
import com.ledger.infrastructure.input.rest.TransactionController;
import com.ledger.infrastructure.input.rest.generated.model.*;
import com.ledger.infrastructure.output.serialization.JsonSupport;
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
        var json = new JsonSupport(tools.jackson.databind.json.JsonMapper.builder().build());
        var controller = new AccountController(accounts, idempotency, history, json);
        when(idempotency.execute(eq("open-account"), eq("key"), eq(new OpenAccount("EUR", BigDecimal.ZERO)), any()))
                .thenReturn(new IdempotentOperations.StoredResponse(201, "{\"id\":\"account\"}"));
        var result = controller.openAccount("key", new OpenAccountDto().currency("EUR").overdraftLimit(BigDecimal.ZERO), null, null);
        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat((String) result.getBody()).contains("account");
        var view = new AccountView("account", "EUR", BigDecimal.ZERO, false, BigDecimal.ZERO);
        when(accounts.get("account")).thenReturn(view);
        assertThat(((AccountDto) controller.getAccount("account").getBody()).getId()).isEqualTo(view.id());
        Instant at = Instant.EPOCH;
        controller.getAccountEntries("account", 0, 50, at);
        controller.getHistoricalBalance("account", at);
        verify(history).entries("account", 0, 50, at);
        verify(history).balance("account", at);
    }

    @Test
    void transactionControllerUsesPortsForRecordingReadingAndReversing() {
        var ledger = mock(LedgerService.class);
        var idempotency = mock(IdempotentOperations.class);
        var controller = new TransactionController(ledger, idempotency, new JsonSupport(tools.jackson.databind.json.JsonMapper.builder().build()));
        when(idempotency.execute(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(3);
            operation.get();
            return new IdempotentOperations.StoredResponse(201, "{}");
        });
        var request = new RecordTransactionDto().description("Settlement").entries(List.of(
                new EntryInputDto().accountId("settlement-EUR").direction(EntryInputDto.DirectionEnum.DEBIT).amount(new BigDecimal("125.50")),
                new EntryInputDto().accountId("merchant").direction(EntryInputDto.DirectionEnum.CREDIT).amount(new BigDecimal("125.50"))));
        assertThat(controller.recordTransaction("key", request, null, null).getStatusCode().value()).isEqualTo(201);
        verify(ledger).record(argThat(command -> command.description().equals("Settlement") && command.entries().size() == 2));
        controller.getTransaction("tx");
        verify(ledger).get("tx");
        controller.reverseTransaction("tx", "reverse-key", null, null);
        verify(ledger).reverse("tx");
        var interceptor = new com.ledger.infrastructure.input.rest.RejectReversalBody();
        var httpRequest = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/transactions/tx/reversals");
        httpRequest.setContent(new byte[]{1});
        assertThatThrownBy(() -> interceptor.preHandle(httpRequest, new org.springframework.mock.web.MockHttpServletResponse(), controller))
                .isInstanceOf(LedgerException.class);
    }
}
