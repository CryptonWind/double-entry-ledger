package com.ledger.infrastructure.input.rest;

import com.ledger.application.model.LedgerModels;
import com.ledger.domain.model.Direction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/** Transport validation belongs to the input adapter, not application commands. */
public final class TransactionRequests {
    private TransactionRequests() { }
    public record Entry(@NotBlank(message = "Account ID is required")
                        @Size(max = 64, message = "Account ID must not exceed 64 characters") String accountId,
                        @NotNull(message = "Direction is required") Direction direction,
                        @io.swagger.v3.oas.annotations.media.Schema(description = "Exact positive decimal; EUR/USD allow 2 decimal places, JPY allows none", example = "125.50")
                        @NotNull(message = "Amount is required") BigDecimal amount) {
        LedgerModels.EntryInput command() { return new LedgerModels.EntryInput(accountId, direction, amount); }
    }
    public record Record(@NotBlank(message = "Description is required")
                         @Size(max = 500, message = "Description must not exceed 500 characters") String description,
                         @NotNull(message = "Entries are required")
                         @Size(min = 2, max = 1000, message = "A transaction needs 2-1000 entries")
                         List<@NotNull(message = "Entry must not be null") @Valid Entry> entries) {
        LedgerModels.RecordTransaction command() {
            return new LedgerModels.RecordTransaction(description, entries.stream().map(Entry::command).toList());
        }
    }
}
