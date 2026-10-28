package com.ledger.application.port.out;

public interface PaymentStore {
    record Reservation(String fingerprint, String transactionId) { }
    Reservation reserve(String paymentId, String fingerprint);
    void complete(String paymentId, String transactionId);
}
