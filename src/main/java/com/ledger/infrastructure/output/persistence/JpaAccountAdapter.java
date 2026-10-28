package com.ledger.infrastructure.output.persistence;

import com.ledger.application.port.out.AccountStore;
import com.ledger.domain.model.Account;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaAccountAdapter implements AccountStore {
    private final AccountRepository repository;
    private final EntityManager entities;

    @Override public Optional<Account> find(String id) { return repository.findById(id).map(this::toDomain); }
    @Override public Optional<Account> lock(String id) { return repository.lockById(id).map(this::toDomain); }
    @Override public Optional<String> currency(String id) {
        return entities.createQuery("select a.currency from AccountEntity a where a.id = :id", String.class)
                .setParameter("id", id).getResultList().stream().findFirst();
    }
    @Override public void insert(Account account) {
        entities.persist(new AccountEntity(account.getId(), account.getCurrency(), account.getOverdraftLimit(),
                account.getBalance(), account.getCreatedAt(), account.getLastPostedAt()));
    }
    @Override public void updateLocked(Account account) {
        var entity = repository.findById(account.getId()).orElseThrow();
        entity.postBalance(account.getBalance(), account.getLastPostedAt());
    }
    private Account toDomain(AccountEntity entity) {
        return new Account(entity.getId(), entity.getCurrency(), entity.getOverdraftLimit(), entity.getBalance(),
                entity.getCreatedAt(), entity.getLastPostedAt());
    }
}
