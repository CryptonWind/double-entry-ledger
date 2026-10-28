package com.ledger.unit;

import com.ledger.domain.model.Account;
import com.ledger.domain.service.MoneyRules;
import com.ledger.domain.model.Direction;
import com.ledger.domain.model.LedgerTransaction;
import com.ledger.application.AccountService;
import com.ledger.application.AccountHistoryService;
import com.ledger.application.PaymentService;
import com.ledger.application.LedgerService;
import com.ledger.support.UnitTestBase;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ArchitectureTest extends UnitTestBase {
    @Test
    void domainHasNoRuntimeFrameworkAnnotationsOrInfrastructureFields() {
        for (var type : List.of(Account.class, MoneyRules.class, LedgerTransaction.class, Direction.class)) {
            assertThat(type.getAnnotations()).allMatch(annotation -> !annotation.annotationType().getName().startsWith("jakarta.")
                    && !annotation.annotationType().getName().startsWith("org.springframework."));
            for (var field : type.getDeclaredFields()) {
                assertThat(field.getType().getName()).doesNotContain("infrastructure", "jakarta.persistence", "org.springframework");
            }
        }
    }

    @Test
    void businessServicesDependOnPortsNotJpaOrConcreteAdapters() {
        for (var type : List.of(AccountService.class, AccountHistoryService.class, LedgerService.class, PaymentService.class)) {
            assertThat(type.getAnnotations()).allMatch(annotation -> !annotation.annotationType().getName().startsWith("org.springframework."));
            for (var field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                assertThat(field.getType().isInterface()).as("%s.%s must depend on an interface", type.getSimpleName(), field.getName()).isTrue();
                assertThat(field.getType().getName()).doesNotContain("infrastructure", "jakarta.persistence", "org.springframework");
            }
            for (var method : type.getDeclaredMethods()) {
                assertThat(method.getAnnotations()).allMatch(annotation -> !annotation.annotationType().getName().startsWith("org.springframework."));
            }
        }
    }
}
