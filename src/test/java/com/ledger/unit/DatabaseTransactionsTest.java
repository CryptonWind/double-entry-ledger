package com.ledger.unit;

import com.ledger.support.UnitTestBase;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DatabaseTransactionsTest extends UnitTestBase {
    @Test
    void retriesTransientLockFailuresAndReturnsSuccessfulResult() {
        var attempts = new AtomicInteger();
        assertThat(transactions.execute(() -> {
            if (attempts.incrementAndGet() < 3) throw new CannotAcquireLockException("Transient lock");
            return "committed";
        })).isEqualTo("committed");
        assertThat(attempts).hasValue(3);
        verify(template, times(3)).execute(any());
    }

    @Test
    void stopsAfterFourAttemptsAndNeverRetriesPermanentErrors() {
        assertThatThrownBy(() -> transactions.execute(() -> { throw new CannotAcquireLockException("Lock unavailable"); }))
                .isInstanceOf(CannotAcquireLockException.class);
        verify(template, times(4)).execute(any());
        clearInvocations(template);
        assertThatThrownBy(() -> transactions.execute(() -> { throw new IllegalArgumentException("Permanent failure"); }))
                .isInstanceOf(IllegalArgumentException.class);
        verify(template).execute(any());
    }
}
