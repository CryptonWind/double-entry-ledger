package com.ledger.infrastructure.output.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "failed_payment_messages")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FailedPaymentMessage {
    @Id @Column(length = 64) private String id;
    @Column(nullable = false, columnDefinition = "longtext") private String payload;
    @Column(nullable = false, length = 2000) private String reason;
    @Column(nullable = false) private Instant failedAt;
}
