package com.ledger.unit;

import com.ledger.application.model.LedgerModels.*;
import com.ledger.application.AccountHistoryService.*;
import com.ledger.infrastructure.input.rest.generated.model.*;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResponseContractTest {
    @Test void applicationResultFieldsMatchGeneratedSchemas() {
        matches(AccountView.class, AccountDto.class);
        matches(EntryView.class, EntryDto.class);
        matches(TransactionView.class, TransactionDto.class);
        matches(HistoryEntry.class, HistoryEntryDto.class);
        matches(EntryPage.class, EntryPageDto.class);
        matches(HistoricalBalance.class, HistoricalBalanceDto.class);
        matches(ApiError.class, ErrorDto.class);
    }
    private void matches(Class<?> result, Class<?> dto) {
        Set<String> components = Arrays.stream(result.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet());
        Set<String> fields = Arrays.stream(dto.getDeclaredFields()).filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                .map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
        assertThat(fields).as("%s must stay aligned with %s", dto.getSimpleName(), result.getSimpleName()).isEqualTo(components);
    }
}
