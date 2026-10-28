package com.ledger.application.model;

import com.ledger.domain.model.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class LedgerModels {
    private LedgerModels() { }

    public record OpenAccount(String currency, BigDecimal overdraftLimit) { }
    public record AccountView(String id, String currency, BigDecimal overdraftLimit,
                              boolean unlimitedOverdraft, BigDecimal balance) { }
    public record EntryInput(String accountId, Direction direction, BigDecimal amount) { }
    public record RecordTransaction(String description, List<EntryInput> entries) { }
    public record EntryView(String id, String accountId, Direction direction, BigDecimal amount,
                            BigDecimal balanceAfter) { }
    public record TransactionView(String id, String description, Instant recordedAt,
                                  String reversesTransactionId, String currency, List<EntryView> entries) { }
    public record ApiError(String code, String field, String message) { }
}
