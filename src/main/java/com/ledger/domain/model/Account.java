package com.ledger.domain.model;

import com.ledger.domain.exception.LedgerException;
import com.ledger.domain.service.MoneyRules;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Business account. No database identity, persistence annotations or framework lifecycle. */
@Getter
@AllArgsConstructor
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
        return new Account(id, currency, limit, BigDecimal.ZERO.setScale(MoneyRules.scale(currency)), now, null);
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
