package com.ledger.application.port.in;

import com.ledger.application.AccountHistoryService.EntryPage;
import com.ledger.application.AccountHistoryService.HistoricalBalance;
import java.time.Instant;

public interface AccountHistoryUseCases {
    EntryPage entries(String id, int page, int size, Instant asOf);
    HistoricalBalance balance(String id, Instant at);
}
