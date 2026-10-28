package com.ledger.application.port.in;

import java.util.function.Supplier;

/** Input port for durable execution/replay. Transport adapters map the stored result to their protocol. */
public interface IdempotentOperations {
    record StoredResponse(int status, String body) { }
    StoredResponse execute(String scope, String key, Object request, Supplier<?> operation);
}
