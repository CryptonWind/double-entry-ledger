package com.ledger.unit;

import com.ledger.domain.service.MoneyRules;
import com.ledger.domain.exception.LedgerException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class MoneyRulesTest {
    @ParameterizedTest
    @CsvSource({"EUR,125.50,2", "USD,98.50,2", "JPY,15000,0"})
    void acceptsDocumentAmountsWithCurrencyPrecision(String currency, String amount, int scale) {
        assertThat(MoneyRules.amount(new BigDecimal(amount), currency, "amount", false))
                .isEqualTo(new BigDecimal(amount).setScale(scale));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"EURO", "ABC", "eur"})
    void rejectsInvalidCurrencyCodes(String code) {
        assertThatThrownBy(() -> MoneyRules.scale(code)).isInstanceOfSatisfying(LedgerException.class,
                error -> assertThat(error.getCode()).isEqualTo("INVALID_CURRENCY"));
    }

    @Test
    void distinguishesValidButUnsupportedGbp() {
        assertThatThrownBy(() -> MoneyRules.scale("GBP")).isInstanceOfSatisfying(LedgerException.class,
                error -> assertThat(error.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void rejectsDocumentFractionalYenExampleWithoutRounding() {
        assertThatThrownBy(() -> MoneyRules.amount(new BigDecimal("100.5"), "JPY", "entries[0].amount", false))
                .isInstanceOfSatisfying(LedgerException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("INVALID_PRECISION");
                    assertThat(error.getField()).isEqualTo("entries[0].amount");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1.00", "-125.50"})
    void entriesRequireStrictlyPositiveAmounts(String value) {
        assertThatThrownBy(() -> MoneyRules.amount(new BigDecimal(value), "EUR", "amount", false))
                .isInstanceOf(LedgerException.class);
    }

    @Test
    void overdraftAllowsZeroButNotNullNegativeOrOverflowAmounts() {
        assertThat(MoneyRules.amount(BigDecimal.ZERO, "EUR", "overdraftLimit", true)).isEqualByComparingTo("0");
        assertThatThrownBy(() -> MoneyRules.amount(null, "EUR", "amount", false)).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> MoneyRules.amount(new BigDecimal("-0.01"), "EUR", "overdraftLimit", true))
                .isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> MoneyRules.amount(new BigDecimal("10000000000000000000000"), "EUR", "amount", false))
                .isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.getCode()).isEqualTo("AMOUNT_TOO_LARGE"));
        assertThatThrownBy(() -> MoneyRules.checkBalance(new BigDecimal("-10000000000000000000000")))
                .isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.getCode()).isEqualTo("BALANCE_OVERFLOW"));
    }

    @Test
    void tenCentsPlusTwentyCentsIsExactlyThirtyCents() {
        assertThat(MoneyRules.amount(new BigDecimal("0.10"), "EUR", "amount", false)
                .add(MoneyRules.amount(new BigDecimal("0.20"), "EUR", "amount", false)))
                .isEqualTo(new BigDecimal("0.30"));
    }
}
