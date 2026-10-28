package com.ledger.application.port.out;

import com.ledger.domain.model.LedgerTransaction;

public interface TransactionAnnouncements {
    /** Persist an announcement atomically with the financial transaction; never publish synchronously. */
    void enqueue(LedgerTransaction transaction);
}
