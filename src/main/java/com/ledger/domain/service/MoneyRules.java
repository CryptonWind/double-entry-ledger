package com.ledger.domain.service;

import com.ledger.domain.exception.LedgerException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Set;

public final class MoneyRules {
    private static final Set<String> SUPPORTED = Set.of("EUR", "USD", "JPY");
    private static final BigDecimal MAX = new BigDecimal("9999999999999999999999.99");

    private MoneyRules() { }

    public static int scale(String currency) {
        try {
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException();
            }
            Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw LedgerException.invalid("INVALID_CURRENCY", "currency", "An ISO 4217 currency code is required");
        }
        if (!SUPPORTED.contains(currency)) {
            throw LedgerException.invalid("UNSUPPORTED_CURRENCY", "currency", "Supported currencies are EUR, USD and JPY");
        }
        return currency.equals("JPY") ? 0 : 2;
    }

    public static BigDecimal amount(BigDecimal value, String currency, String field, boolean allowZero) {
        int scale = scale(currency);
        if (value == null || value.signum() < 0 || (!allowZero && value.signum() == 0)) {
            throw LedgerException.invalid("INVALID_AMOUNT", field, allowZero ? "Amount must be non-negative" : "Amount must be positive");
        }
        if (value.scale() > scale) {
            throw LedgerException.invalid("INVALID_PRECISION", field, currency + " permits at most " + scale + " decimal places");
        }
        if (value.compareTo(MAX) > 0) {
            throw LedgerException.invalid("AMOUNT_TOO_LARGE", field, "Amount exceeds storage limits");
        }
        return value.setScale(scale);
    }

    public static void checkBalance(BigDecimal value) {
        if (value.abs().compareTo(MAX) > 0) {
            throw LedgerException.invalid("BALANCE_OVERFLOW", "entries", "Resulting balance exceeds storage limits");
        }
    }
}
