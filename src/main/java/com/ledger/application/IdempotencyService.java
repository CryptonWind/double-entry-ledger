package com.ledger.application;

import com.ledger.application.port.out.UnitOfWork;
import com.ledger.application.port.out.JsonCodec;
import com.ledger.domain.exception.LedgerException;
import com.ledger.application.model.LedgerModels.ApiError;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import com.ledger.application.port.out.IdempotencyStore;
import com.ledger.application.port.in.IdempotentOperations;

@RequiredArgsConstructor
@Log4j2
public class IdempotencyService implements IdempotentOperations {
    private final IdempotencyStore store;
    private final JsonCodec json;
    private final UnitOfWork transactions;

    public StoredResponse execute(String scope, String key, Object request, Supplier<?> operation) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new LedgerException(400, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key",
                    "Use 1-128 ASCII letters, digits, dots, underscores, colons or hyphens");
        }
        String fingerprint = json.fingerprint(request);
        return transactions.execute(() -> {
            // The unique key serializes concurrent attempts across application instances.
            var row = store.reserve(scope, key, fingerprint);
            if (!fingerprint.equals(row.fingerprint())) {
                throw new LedgerException(409, "IDEMPOTENCY_CONFLICT", "Idempotency-Key",
                        "This key was already used with a different request");
            }
            if (row.status() != null) {
                log.info("Replaying idempotent response scope={} key={} status={}", scope, key, row.status());
                return new StoredResponse(row.status(), row.body());
            }
            StoredResponse response;
            try {
                response = new StoredResponse(201, json.write(operation.get()));
            } catch (LedgerException e) {
                // Domain validation must finish before any financial state is changed.
                response = new StoredResponse(e.getStatus(), json.write(new ApiError(e.getCode(), e.getField(), e.getMessage())));
            }
            store.complete(scope, key, response.status(), response.body());
            log.info("Completed idempotent operation scope={} key={} status={}", scope, key, response.status());
            return response;
        });
    }
}
