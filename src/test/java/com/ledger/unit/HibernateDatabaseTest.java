package com.ledger.unit;

import com.ledger.infrastructure.output.persistence.HibernateDatabase;
import com.ledger.support.UnitTestBase;
import jakarta.persistence.Query;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HibernateDatabaseTest extends UnitTestBase {
    @Test
    void nativeReservationBindsParametersWithoutInterpolatingRequestValues() {
        var adapter = new HibernateDatabase(entities);
        Query query = mock(Query.class);
        when(entities.createNativeQuery("INSERT INTO example(a,b) VALUES (?1, ?2)")).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);
        assertThat(adapter.update("INSERT INTO example(a,b) VALUES (?, ?)", "untrusted-value", 42)).isEqualTo(1);
        verify(query).setParameter(1, "untrusted-value");
        verify(query).setParameter(2, 42);
    }

    @Test
    void scalarReadsRequireExactlyOneRow() {
        var adapter = new HibernateDatabase(entities);
        Query query = mock(Query.class);
        when(entities.createNativeQuery("SELECT count(*) FROM example", Long.class)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(2L));
        assertThat(adapter.queryForObject("SELECT count(*) FROM example", Long.class)).isEqualTo(2L);
        when(query.getResultList()).thenReturn(List.of());
        assertThatThrownBy(() -> adapter.queryForObject("SELECT count(*) FROM example", Long.class))
                .isInstanceOf(IllegalStateException.class);
    }
}
