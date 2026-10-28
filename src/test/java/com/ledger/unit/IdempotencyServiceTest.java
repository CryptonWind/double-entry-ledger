package com.ledger.unit;

import com.ledger.infrastructure.output.persistence.IdempotencyRecord;
import com.ledger.domain.exception.LedgerException;
import com.ledger.support.UnitTestBase;
import com.ledger.application.model.LedgerModels.OpenAccount;
import jakarta.persistence.LockModeType;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest extends UnitTestBase {
    @Test
    void executesOnceAndReplaysStoredStatusAndBody() {
        var request = new OpenAccount("EUR", null);
        var stored = mock(IdempotencyRecord.class);
        when(stored.getFingerprint()).thenReturn(json.fingerprint(request));
        when(entities.find(eq(IdempotencyRecord.class), any(IdempotencyRecord.Key.class), eq(LockModeType.PESSIMISTIC_WRITE)))
                .thenReturn(stored);
        var calls = new AtomicInteger();
        var first = idempotency.execute("open-account", "key", request, () -> { calls.incrementAndGet(); return "created"; });
        verify(stored).complete(201, "\"created\"");
        when(stored.getResponseStatus()).thenReturn(first.status());
        when(stored.getResponseBody()).thenReturn(first.body());
        assertThat(idempotency.execute("open-account", "key", request, () -> { calls.incrementAndGet(); return "wrong"; }))
                .isEqualTo(first);
        assertThat(calls).hasValue(1);
    }

    @Test
    void refusesSameKeyWithDifferentPayload() {
        var stored = mock(IdempotencyRecord.class);
        when(stored.getFingerprint()).thenReturn(json.fingerprint(new OpenAccount("EUR", null)));
        when(entities.find(eq(IdempotencyRecord.class), any(IdempotencyRecord.Key.class), eq(LockModeType.PESSIMISTIC_WRITE)))
                .thenReturn(stored);
        assertThatThrownBy(() -> idempotency.execute("open-account", "key", new OpenAccount("USD", null),
                () -> { throw new AssertionError("Must not execute"); }))
                .isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void storesBusinessRefusalButDoesNotSwallowInfrastructureFailure() {
        var request = new OpenAccount("EUR", null);
        var stored = mock(IdempotencyRecord.class);
        when(stored.getFingerprint()).thenReturn(json.fingerprint(request));
        when(entities.find(eq(IdempotencyRecord.class), any(IdempotencyRecord.Key.class), eq(LockModeType.PESSIMISTIC_WRITE)))
                .thenReturn(stored);
        var refused = idempotency.execute("open-account", "key", request,
                () -> { throw LedgerException.invalid("REFUSED", "amount", "Refused"); });
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body()).contains("REFUSED");
        verify(stored).complete(422, refused.body());
        assertThatThrownBy(() -> idempotency.execute("open-account", "other", request,
                () -> { throw new IllegalStateException("Database unavailable"); })).isInstanceOf(IllegalStateException.class);
        verify(stored, times(1)).complete(anyInt(), anyString());
    }

    @Test
    void invalidKeyNeverReachesDatabase() {
        assertThatThrownBy(() -> idempotency.execute("scope", "contains spaces", "request", () -> null))
                .isInstanceOf(LedgerException.class);
        verifyNoInteractions(database);
    }
}
