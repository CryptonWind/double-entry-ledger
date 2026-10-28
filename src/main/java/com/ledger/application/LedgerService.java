package com.ledger.application;

import com.ledger.application.port.out.AccountStore;
import com.ledger.domain.model.Account;
import com.ledger.domain.exception.LedgerException;
import com.ledger.application.port.in.TransactionUseCases;
import com.ledger.application.port.out.TransactionAnnouncements;
import com.ledger.application.port.out.TransactionStore;
import com.ledger.domain.model.LedgerTransaction;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import com.ledger.application.port.out.UnitOfWork;

import static com.ledger.application.model.LedgerModels.*;

@RequiredArgsConstructor
@Log4j2
public class LedgerService implements TransactionUseCases {
    private final AccountStore accounts;
    private final TransactionStore transactions;
    private final TransactionAnnouncements announcements;
    private final UnitOfWork work;

    @Override public TransactionView record(RecordTransaction request) {
        List<LedgerTransaction.Posting> postings = request.entries() == null ? null : request.entries().stream()
                .map(e -> e == null ? null : new LedgerTransaction.Posting(e.accountId(), e.direction(), e.amount())).toList();
        return record(request.description(), postings, null);
    }

    @Override public TransactionView reverse(String originalId) {
        var original = transactions.lock(originalId).orElseThrow(() ->
                new LedgerException(404, "TRANSACTION_NOT_FOUND", "transactionId", "Unknown transaction: " + originalId));
        var postings = original.reversalPostings();
        if (transactions.hasReversal(originalId)) {
            throw new LedgerException(409, "ALREADY_REVERSED", "transactionId", "Transaction has already been reversed");
        }
        log.info("Reversing transaction transactionId={}", originalId);
        return record("Reversal of " + originalId, postings, originalId);
    }

    private TransactionView record(String description, List<LedgerTransaction.Posting> postings, String reversesId) {
        LedgerTransaction.validateRequest(description, postings);
        Map<String, Account> locked = new LinkedHashMap<>();
        postings.stream().map(LedgerTransaction.Posting::accountId).distinct().sorted().forEach(id -> {
            int index = java.util.stream.IntStream.range(0, postings.size())
                    .filter(i -> postings.get(i).accountId().equals(id)).findFirst().orElseThrow();
            locked.put(id, accounts.lock(id).orElseThrow(() ->
                    LedgerException.invalid("UNKNOWN_ACCOUNT", "entries[" + index + "].accountId", "Unknown account: " + id)));
        });
        var transaction = LedgerTransaction.record(UUID.randomUUID().toString(), description, postings, locked, reversesId, Instant.now());
        transactions.append(transaction);
        locked.values().forEach(accounts::updateLocked);
        announcements.enqueue(transaction);
        log.info("Recorded transaction transactionId={} currency={} entries={} reversesTransactionId={}",
                transaction.id(), transaction.currency(), transaction.entries().size(), reversesId);
        return view(transaction);
    }

    @Override
    public TransactionView get(String id) {
        log.debug("Reading transaction transactionId={}", id);
        return work.read(() -> transactions.find(id).map(LedgerService::view).orElseThrow(() ->
                new LedgerException(404, "TRANSACTION_NOT_FOUND", "transactionId", "Unknown transaction: " + id)));
    }

    public static TransactionView view(LedgerTransaction transaction) {
        return new TransactionView(transaction.id(), transaction.description(), transaction.recordedAt(),
                transaction.reversesTransactionId(), transaction.currency(), transaction.entries().stream()
                .map(e -> new EntryView(e.id(), e.accountId(), e.direction(), e.amount(), e.balanceAfter())).toList());
    }
}
