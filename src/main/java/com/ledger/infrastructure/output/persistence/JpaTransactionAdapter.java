package com.ledger.infrastructure.output.persistence;

import com.ledger.domain.service.MoneyRules;
import com.ledger.application.port.out.TransactionStore;
import com.ledger.domain.model.Direction;
import com.ledger.domain.model.LedgerTransaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaTransactionAdapter implements TransactionStore {
    private final LedgerTransactionRepository repository;
    private final LedgerEntryRepository entries;
    private final EntityManager entities;

    @Override public Optional<LedgerTransaction> find(String id) { return repository.findById(id).map(this::toDomain); }
    @Override public Optional<LedgerTransaction> lock(String id) {
        return Optional.ofNullable(entities.find(LedgerTransactionEntity.class, id, LockModeType.PESSIMISTIC_WRITE)).map(this::toDomain);
    }
    @Override public boolean hasReversal(String id) { return repository.existsByReversesTransactionId(id); }
    @Override public void append(LedgerTransaction transaction) {
        entities.persist(new LedgerTransactionEntity(transaction.id(), transaction.description(), transaction.recordedAt(),
                transaction.reversesTransactionId(), transaction.currency()));
        entities.flush();
        int position = 0;
        for (var entry : transaction.entries()) {
            entities.persist(new LedgerEntryEntity(entry.id(), transaction.id(), position++, entry.accountId(),
                    entry.direction().name(), entry.amount(), entry.balanceAfter()));
        }
        entities.flush();
    }
    private LedgerTransaction toDomain(LedgerTransactionEntity entity) {
        var stored = entries.findByTransactionIdOrderByPosition(entity.getId());
        // Transaction currency is persisted explicitly; reads do not load account state.
        String currency = entity.getCurrency();
        int scale = MoneyRules.scale(currency);
        return new LedgerTransaction(entity.getId(), entity.getDescription(), entity.getRecordedAt(), entity.getReversesTransactionId(),
                currency, stored.stream().map(e -> new LedgerTransaction.Entry(e.getId(), e.getAccountId(),
                        Direction.valueOf(e.getDirection()), e.getAmount().setScale(scale), e.getBalanceAfter().setScale(scale))).toList());
    }
}
