package com.ledger.infrastructure.output.persistence;

import com.ledger.application.port.out.IdempotencyStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaIdempotencyAdapter implements IdempotencyStore {
    private final HibernateDatabase database;
    private final EntityManager entities;
    @Override public Reservation reserve(String scope, String key, String fingerprint) {
        database.update("INSERT INTO idempotency_records(scope, request_key, fingerprint) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE request_key = request_key", scope, key, fingerprint);
        var entity = entities.find(IdempotencyRecord.class, new IdempotencyRecord.Key(scope, key), LockModeType.PESSIMISTIC_WRITE);
        return new Reservation(entity.getFingerprint(), entity.getResponseStatus(), entity.getResponseBody());
    }
    @Override public void complete(String scope, String key, int status, String body) {
        entities.find(IdempotencyRecord.class, new IdempotencyRecord.Key(scope, key), LockModeType.PESSIMISTIC_WRITE).complete(status, body);
    }
}
