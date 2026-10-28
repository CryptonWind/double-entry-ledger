package com.ledger.application.port.in;

import com.ledger.application.model.LedgerModels.RecordTransaction;
import com.ledger.application.model.LedgerModels.TransactionView;

public interface LedgerService {
    TransactionView record(RecordTransaction request);
    TransactionView reverse(String id);
    TransactionView get(String id);
}
