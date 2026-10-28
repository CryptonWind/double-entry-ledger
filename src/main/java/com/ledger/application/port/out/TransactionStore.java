package com.ledger.application.port.out;

import com.ledger.domain.model.LedgerTransaction;
import java.util.Optional;

public interface TransactionStore {
    Optional<LedgerTransaction> find(String id);
    Optional<LedgerTransaction> lock(String id);
    boolean hasReversal(String id);
    void append(LedgerTransaction transaction);
}
