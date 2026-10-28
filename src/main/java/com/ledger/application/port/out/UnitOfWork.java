package com.ledger.application.port.out;

import java.util.function.Supplier;

public interface UnitOfWork {
    <T> T execute(Supplier<T> action);
    /** Join an existing write boundary without changing its business-refusal semantics, or start one. */
    default <T> T required(Supplier<T> action) { return execute(action); }
    default <T> T read(Supplier<T> action) { return action.get(); }
    default <T> T snapshot(Supplier<T> action) { return read(action); }
}
