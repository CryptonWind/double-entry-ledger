package com.ledger.application.port.out;

import com.ledger.application.model.EntryPage;
import java.math.BigDecimal;
import java.time.Instant;

public interface AccountHistoryReader {
    EntryPage entries(String accountId, int page, int size, Instant asOf, int scale);
    BigDecimal balance(String accountId, Instant at);
}
