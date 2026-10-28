package com.ledger.infrastructure.output.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface LedgerTransactionRepository extends Repository<LedgerTransactionEntity, String> {
    Optional<LedgerTransactionEntity> findById(String id);
    boolean existsByReversesTransactionId(String id);
}
