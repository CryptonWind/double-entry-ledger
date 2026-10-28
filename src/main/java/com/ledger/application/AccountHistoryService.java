package com.ledger.application;

import com.ledger.application.port.in.AccountUseCases;
import com.ledger.application.port.out.AccountHistoryReader;
import com.ledger.domain.service.MoneyRules;
import com.ledger.domain.exception.LedgerException;
import com.ledger.domain.model.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import com.ledger.application.port.out.UnitOfWork;

@RequiredArgsConstructor
@Log4j2
public class AccountHistoryService implements com.ledger.application.port.in.AccountHistoryUseCases {
    private final AccountUseCases accounts;
    private final AccountHistoryReader reader;
    private final UnitOfWork work;
    public record HistoryEntry(String id, String transactionId, Instant recordedAt, int position,
                               Direction direction, BigDecimal amount, BigDecimal balanceAfter) { }
    public record EntryPage(List<HistoryEntry> content, int page, int size, long totalElements, long totalPages, Instant asOf) { }
    public record HistoricalBalance(String accountId, String currency, Instant at, BigDecimal balance) { }

    public EntryPage entries(String id, int page, int size, Instant asOf) {
        if (page < 0 || size < 1 || size > 200 || (long) page * size > Integer.MAX_VALUE) {
            throw new LedgerException(400, "INVALID_PAGINATION", "page,size", "Page must be non-negative and size must be 1-200");
        }
        return work.snapshot(() -> {
            var account = accounts.get(id);
            log.debug("Reading account history accountId={} page={} size={} asOf={}", id, page, size, asOf);
            return reader.entries(id, page, size, asOf == null ? Instant.now() : asOf, MoneyRules.scale(account.currency()));
        });
    }
    public HistoricalBalance balance(String id, Instant at) {
        if (at == null) throw new LedgerException(400, "MISSING_INSTANT", "at", "An ISO-8601 instant is required");
        return work.read(() -> {
            var account = accounts.get(id);
            var result = reader.balance(id, at);
            log.debug("Reading historical balance accountId={} at={}", id, at);
            return new HistoricalBalance(id, account.currency(), at,
                    (result == null ? BigDecimal.ZERO : result).setScale(MoneyRules.scale(account.currency())));
        });
    }
}
