package com.ledger.infrastructure.output.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "ledger_entries")
@Immutable
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntryEntity {
    @Id @Column(length = 64)
    private String id;
    @Column(nullable = false, length = 64)
    private String transactionId;
    @Column(nullable = false)
    private int position;
    @Column(nullable = false, length = 64)
    private String accountId;
    @Column(nullable = false, length = 6)
    private String direction;
    @Column(nullable = false, precision = 24, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, precision = 28, scale = 2)
    private BigDecimal balanceAfter;
}
