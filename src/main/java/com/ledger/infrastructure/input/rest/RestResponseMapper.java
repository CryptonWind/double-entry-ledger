package com.ledger.infrastructure.input.rest;

import com.ledger.application.model.LedgerModels.*;
import com.ledger.application.AccountHistoryService.*;
import com.ledger.infrastructure.input.rest.generated.model.*;

/** Explicit typed mapping: generated schema changes are visible to the compiler. */
public final class RestResponseMapper {
    private RestResponseMapper() { }

    public static AccountDto account(AccountView value) {
        return new AccountDto().id(value.id()).currency(AccountDto.CurrencyEnum.fromValue(value.currency()))
                .overdraftLimit(value.overdraftLimit()).unlimitedOverdraft(value.unlimitedOverdraft()).balance(value.balance());
    }
    public static EntryDto entry(EntryView value) {
        return new EntryDto().id(value.id()).accountId(value.accountId()).direction(EntryDto.DirectionEnum.fromValue(value.direction().name()))
                .amount(value.amount()).balanceAfter(value.balanceAfter());
    }
    public static TransactionDto transaction(TransactionView value) {
        return new TransactionDto().id(value.id()).description(value.description()).recordedAt(value.recordedAt())
                .reversesTransactionId(value.reversesTransactionId()).currency(TransactionDto.CurrencyEnum.fromValue(value.currency()))
                .entries(value.entries().stream().map(RestResponseMapper::entry).toList());
    }
    public static HistoryEntryDto historyEntry(HistoryEntry value) {
        return new HistoryEntryDto().id(value.id()).transactionId(value.transactionId()).recordedAt(value.recordedAt())
                .position(value.position()).direction(HistoryEntryDto.DirectionEnum.fromValue(value.direction().name()))
                .amount(value.amount()).balanceAfter(value.balanceAfter());
    }
    public static EntryPageDto page(EntryPage value) {
        return new EntryPageDto().content(value.content().stream().map(RestResponseMapper::historyEntry).toList())
                .page(value.page()).size(value.size()).totalElements(value.totalElements()).totalPages(value.totalPages()).asOf(value.asOf());
    }
    public static HistoricalBalanceDto balance(HistoricalBalance value) {
        return new HistoricalBalanceDto().accountId(value.accountId()).currency(value.currency()).at(value.at()).balance(value.balance());
    }
}
