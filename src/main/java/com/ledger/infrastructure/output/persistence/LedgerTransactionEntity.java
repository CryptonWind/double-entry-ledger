package com.ledger.infrastructure.output.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "ledger_transactions")
@Immutable
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerTransactionEntity {
    @Id @Column(length = 64)
    private String id;
    @Column(nullable = false, length = 500)
    private String description;
    @Column(nullable = false)
    private Instant recordedAt;
    @Column(length = 64)
    private String reversesTransactionId;
}
