package com.ledger.domain.model;

import com.ledger.domain.exception.LedgerException;
import com.ledger.domain.service.MoneyRules;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Business account. No database identity, persistence annotations or framework lifecycle. */
@Getter
@AllArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class Account {
    private final String id;
    private final String currency;
    private final BigDecimal overdraftLimit;
    private BigDecimal balance;
    private final Instant createdAt;
    private Instant lastPostedAt;

    public static Account open(String id, String currency, BigDecimal overdraftLimit, Instant now) {
        BigDecimal limit = MoneyRules.amount(overdraftLimit == null ? BigDecimal.ZERO : overdraftLimit,
                currency, "overdraftLimit", true);
        return restore(id, currency, limit, BigDecimal.ZERO.setScale(MoneyRules.scale(currency)), now, null);
    }

    public static Account restore(String id, String currency, BigDecimal limit, BigDecimal balance,
            Instant createdAt, Instant lastPostedAt) {
        if (id == null || id.isBlank() || id.length() > 64 || createdAt == null || balance == null) {
            throw new IllegalArgumentException("Account identity, creation time and balance are required");
        }
        int scale = MoneyRules.scale(currency);
        BigDecimal normalizedLimit = limit == null ? null : MoneyRules.amount(limit.stripTrailingZeros(), currency, "overdraftLimit", true);
        MoneyRules.checkBalance(balance);
        try {
            balance = balance.setScale(scale, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Stored balance does not fit currency precision", e);
        }
        if (normalizedLimit != null && balance.compareTo(normalizedLimit.negate()) < 0) {
            throw new IllegalArgumentException("Stored account exceeds its overdraft limit");
        }
        return new Account(id, currency, normalizedLimit, balance, createdAt, lastPostedAt);
    }

    public BigDecimal validatePosting(BigDecimal delta, String field) {
        BigDecimal result = balance.add(delta);
        MoneyRules.checkBalance(result);
        if (overdraftLimit != null && result.compareTo(overdraftLimit.negate()) < 0) {
            throw LedgerException.invalid("OVERDRAFT_EXCEEDED", field, "Overdraft limit exceeded for account " + id);
        }
        return result;
    }

    public void post(BigDecimal delta, Instant recordedAt) {
        balance = validatePosting(delta, "entries");
        lastPostedAt = recordedAt;
    }
}
