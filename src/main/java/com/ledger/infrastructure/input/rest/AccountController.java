package com.ledger.infrastructure.input.rest;

import com.ledger.application.model.LedgerModels;
import com.ledger.application.port.in.*;
import com.ledger.infrastructure.input.rest.generated.api.AccountsApi;
import com.ledger.infrastructure.input.rest.generated.model.*;
import com.ledger.application.port.out.JsonCodec;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements the generated input contract; business models remain independent of generated DTOs. */
@RestController
@RequiredArgsConstructor
public class AccountController implements AccountsApi {
    private final AccountUseCases accounts;
    private final IdempotentOperations idempotency;
    private final AccountHistoryUseCases history;
    private final JsonCodec json;

    @Override public ResponseEntity<?> openAccount(String key, OpenAccountDto request, String correlationId, String traceparent) {
        var command = new LedgerModels.OpenAccount(request.getCurrency(), request.getOverdraftLimit());
        var result = idempotency.execute("open-account", key, command, () -> convert(accounts.open(command), AccountDto.class));
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
    @Override public ResponseEntity<?> getAccount(String id) {
        return ResponseEntity.ok(convert(accounts.get(id), AccountDto.class));
    }
    @Override public ResponseEntity<?> getAccountEntries(String id, Integer page, Integer size, Instant asOf) {
        return ResponseEntity.ok(convert(history.entries(id, page == null ? 0 : page, size == null ? 50 : size, asOf), EntryPageDto.class));
    }
    @Override public ResponseEntity<?> getHistoricalBalance(String id, Instant at) {
        return ResponseEntity.ok(convert(history.balance(id, at), HistoricalBalanceDto.class));
    }
    private <T> T convert(Object result, Class<T> type) { return json.read(json.write(result), type); }
}
