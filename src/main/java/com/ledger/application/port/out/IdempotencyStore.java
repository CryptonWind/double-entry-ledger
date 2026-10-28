package com.ledger.application.port.out;

public interface IdempotencyStore {
    record Reservation(String fingerprint, Integer status, String body) { }
    Reservation reserve(String scope, String key, String fingerprint);
    void complete(String scope, String key, int status, String body);
}
