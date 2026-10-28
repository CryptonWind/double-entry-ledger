package com.ledger.infrastructure.output.persistence;

import java.util.List;
import org.springframework.data.repository.Repository;

public interface LedgerEntryRepository extends Repository<LedgerEntryEntity, String> {
    List<LedgerEntryEntity> findByTransactionIdOrderByPosition(String transactionId);
}
