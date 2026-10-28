package com.ledger.application.port.out;

import java.util.function.Supplier;

public interface UnitOfWork {
    <T> T execute(Supplier<T> action);
    /**
     * Join an existing write boundary or start a retryable one. A joined transaction
     * is never retried here: its owner must restart the entire operation after a
     * transient failure. Business refusals must occur before persistent writes.
     */
    default <T> T required(Supplier<T> action) { return execute(action); }
    default <T> T read(Supplier<T> action) { return action.get(); }
    default <T> T snapshot(Supplier<T> action) { return read(action); }
}
