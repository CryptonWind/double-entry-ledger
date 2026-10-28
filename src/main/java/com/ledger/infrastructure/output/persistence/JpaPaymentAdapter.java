package com.ledger.infrastructure.output.persistence;

import com.ledger.application.port.out.PaymentStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaPaymentAdapter implements PaymentStore {
    private final HibernateDatabase database;
    private final EntityManager entities;
    @Override public Reservation reserve(String id, String fingerprint) {
        database.update("INSERT INTO processed_payments(payment_id, fingerprint) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE payment_id = payment_id", id, fingerprint);
        var entity = entities.find(ProcessedPayment.class, id, LockModeType.PESSIMISTIC_WRITE);
        return new Reservation(entity.getFingerprint(), entity.getTransactionId());
    }
    @Override public void complete(String id, String transactionId) {
        entities.find(ProcessedPayment.class, id, LockModeType.PESSIMISTIC_WRITE).complete(transactionId);
    }
}
