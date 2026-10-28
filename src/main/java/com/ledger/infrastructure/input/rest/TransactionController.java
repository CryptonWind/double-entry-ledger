package com.ledger.infrastructure.input.rest;

import com.ledger.application.model.LedgerModels;
import com.ledger.application.port.in.*;
import com.ledger.application.port.out.JsonCodec;
import com.ledger.domain.model.Direction;
import com.ledger.infrastructure.input.rest.generated.api.TransactionsApi;
import com.ledger.infrastructure.input.rest.generated.model.RecordTransactionDto;
import com.ledger.infrastructure.input.rest.generated.model.TransactionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TransactionController implements TransactionsApi {
    private final LedgerService ledger;
    private final IdempotentOperations idempotency;
    private final JsonCodec json;

    @Override public ResponseEntity<?> recordTransaction(String key, RecordTransactionDto request, String correlationId, String traceparent) {
        var command = new LedgerModels.RecordTransaction(request.getDescription(), request.getEntries().stream()
                .map(entry -> new LedgerModels.EntryInput(entry.getAccountId(), Direction.valueOf(entry.getDirection().getValue()), entry.getAmount()))
                .toList());
        return response(idempotency.execute("record-transaction", key, command,
                () -> RestResponseMapper.transaction(ledger.record(command))));
    }
    @Override @NoRequestBody
    public ResponseEntity<?> reverseTransaction(String id, String key, String correlationId, String traceparent) {
        return response(idempotency.execute("reverse-transaction", key, id,
                () -> RestResponseMapper.transaction(ledger.reverse(id))));
    }
    @Override public ResponseEntity<?> getTransaction(String id) {
        return ResponseEntity.ok(RestResponseMapper.transaction(ledger.get(id)));
    }
    private ResponseEntity<?> response(IdempotentOperations.StoredResponse result) {
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
