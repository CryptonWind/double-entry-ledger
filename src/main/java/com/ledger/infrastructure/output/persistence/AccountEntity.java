package com.ledger.infrastructure.output.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AccountEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(precision = 24, scale = 2)
    private BigDecimal overdraftLimit;
    @Column(nullable = false, precision = 24, scale = 2)
    private BigDecimal balance;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant lastPostedAt;

    public AccountEntity(String id, String currency, BigDecimal overdraftLimit) {
        this.id = id;
        this.currency = currency;
        this.overdraftLimit = overdraftLimit;
        this.balance = BigDecimal.ZERO;
        this.createdAt = Instant.now();
    }

    public void postBalance(BigDecimal newBalance, Instant recordedAt) {
        this.balance = newBalance;
        this.lastPostedAt = recordedAt;
    }
}
