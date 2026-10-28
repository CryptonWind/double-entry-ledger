package com.ledger.unit;

import com.ledger.application.model.LedgerModels.*;
import com.ledger.application.model.*;
import com.ledger.infrastructure.input.rest.generated.model.*;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResponseContractTest {
    private record DecimalResponse(java.math.BigDecimal amount) { }
    private static class WrongAmountDto { private String amount; }

    @Test void detectsMonetaryTypeDrift() {
        assertThatThrownBy(() -> matches(DecimalResponse.class, WrongAmountDto.class)).isInstanceOf(AssertionError.class);
    }
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
        for (var component : result.getRecordComponents()) {
            try {
                var field = dto.getDeclaredField(component.getName());
                compatible(component.getGenericType(), field.getGenericType());
            } catch (NoSuchFieldException e) {
                throw new AssertionError(e);
            }
        }
    }

    private void compatible(java.lang.reflect.Type source, java.lang.reflect.Type target) {
        if (source instanceof java.lang.reflect.ParameterizedType left
                && target instanceof java.lang.reflect.ParameterizedType right) {
            assertThat(left.getRawType()).isEqualTo(right.getRawType());
            assertThat(left.getActualTypeArguments()).hasSameSizeAs(right.getActualTypeArguments());
            for (int i = 0; i < left.getActualTypeArguments().length; i++) {
                compatible(left.getActualTypeArguments()[i], right.getActualTypeArguments()[i]);
            }
            return;
        }
        Class<?> left = (Class<?>) source;
        Class<?> right = (Class<?>) target;
        if (left.isRecord()) { matches(left, right); return; }
        if (right.isEnum() && (left == String.class || left.isEnum())) {
            if (left.isEnum()) assertThat(enumValues(right)).containsExactlyInAnyOrderElementsOf(enumValues(left));
            return;
        }
        assertThat(org.springframework.util.ClassUtils.resolvePrimitiveIfNecessary(right))
                .as("Response type for %s", source.getTypeName())
                .isEqualTo(org.springframework.util.ClassUtils.resolvePrimitiveIfNecessary(left));
    }

    private java.util.List<String> enumValues(Class<?> type) {
        return Arrays.stream(type.getEnumConstants()).map(Object::toString).toList();
    }
}
