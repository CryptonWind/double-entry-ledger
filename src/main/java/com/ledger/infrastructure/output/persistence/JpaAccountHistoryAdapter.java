package com.ledger.infrastructure.output.persistence;

import com.ledger.application.model.EntryPage;
import com.ledger.application.model.HistoryEntry;
import com.ledger.application.port.out.AccountHistoryReader;
import com.ledger.domain.model.Direction;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaAccountHistoryAdapter implements AccountHistoryReader {
    private final EntityManager entities;
    @Override public EntryPage entries(String id, int page, int size, Instant at, int scale) {
        String from = " from LedgerEntryEntity e, LedgerTransactionEntity t where e.transactionId = t.id"
                + " and e.accountId = :accountId and t.recordedAt <= :at";
        long count = entities.createQuery("select count(e)" + from, Long.class)
                .setParameter("accountId", id).setParameter("at", at).getSingleResult();
        var content = entities.createQuery("select e.id, e.transactionId, t.recordedAt, e.position, e.direction, e.amount, e.balanceAfter"
                        + from + " order by t.recordedAt desc, t.id desc, e.position desc", Object[].class)
                .setParameter("accountId", id).setParameter("at", at).setFirstResult(page * size).setMaxResults(size)
                .getResultList().stream().map(row -> new HistoryEntry((String) row[0], (String) row[1], (Instant) row[2],
                        (Integer) row[3], Direction.valueOf((String) row[4]), ((BigDecimal) row[5]).setScale(scale),
                        ((BigDecimal) row[6]).setScale(scale))).toList();
        return new EntryPage(content, page, size, count, (count + size - 1) / size, at);
    }
    @Override public BigDecimal balance(String id, Instant at) {
        return entities.createQuery("select sum(case when e.direction = 'CREDIT' then e.amount else -e.amount end) "
                        + "from LedgerEntryEntity e, LedgerTransactionEntity t where e.transactionId = t.id "
                        + "and e.accountId = :accountId and t.recordedAt <= :at", BigDecimal.class)
                .setParameter("accountId", id).setParameter("at", at).getSingleResult();
    }
}
