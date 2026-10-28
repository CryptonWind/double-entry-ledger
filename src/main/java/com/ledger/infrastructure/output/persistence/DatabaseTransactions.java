package com.ledger.infrastructure.output.persistence;

import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Log4j2
public class DatabaseTransactions implements com.ledger.application.port.out.UnitOfWork {
    private final TransactionTemplate transactions;

    @Override
    public <T> T read(Supplier<T> action) {
        var read = new TransactionTemplate(transactions.getTransactionManager());
        read.setReadOnly(true);
        return read.execute(status -> action.get());
    }

    @Override
    public <T> T snapshot(Supplier<T> action) {
        var snapshot = new TransactionTemplate(transactions.getTransactionManager());
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return snapshot.execute(status -> action.get());
    }

    public <T> T execute(Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transactions.execute(status -> action.get());
            } catch (TransientDataAccessException e) {
                if (attempt >= 3) {
                    log.error("Database transaction exhausted transient retries attempts={}", attempt + 1, e);
                    throw e;
                }
                log.warn("Retrying database transaction attempt={} reason={}", attempt + 2, e.getClass().getSimpleName());
                try {
                    Thread.sleep(25L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
