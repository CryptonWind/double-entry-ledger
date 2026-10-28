package com.ledger.application.port.in;

import com.ledger.application.model.EntryPage;
import com.ledger.application.model.HistoricalBalance;
import java.time.Instant;

public interface AccountHistoryUseCases {
    EntryPage entries(String id, int page, int size, Instant asOf);
    HistoricalBalance balance(String id, Instant at);
}
