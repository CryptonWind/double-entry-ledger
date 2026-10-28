package com.ledger.infrastructure.output.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "processed_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedPayment {
    @Id @Column(length = 128) private String paymentId;
    @Column(nullable = false, length = 64, columnDefinition = "char(64)") private String fingerprint;
    @Column(length = 64) private String transactionId;

    public void complete(String id) { transactionId = id; }
}
