package com.ledger.infrastructure.output.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Native SQL escape hatch executed exclusively through Hibernate, on the JPA transaction. */
@Repository
@RequiredArgsConstructor
@Transactional
public class HibernateDatabase {
    private final EntityManager entities;

    public int update(String sql, Object... arguments) {
        var query = entities.createNativeQuery(numberParameters(sql));
        bind(query, arguments);
        return query.executeUpdate();
    }

    @Transactional(readOnly = true)
    public <T> List<T> queryForList(String sql, Class<T> type, Object... arguments) {
        var query = entities.createNativeQuery(numberParameters(sql), type);
        bind(query, arguments);
        @SuppressWarnings("unchecked") List<T> results = query.getResultList();
        return results;
    }

    @Transactional(readOnly = true)
    public <T> T queryForObject(String sql, Class<T> type, Object... arguments) {
        var result = queryForList(sql, type, arguments);
        if (result.size() != 1) throw new IllegalStateException("Expected exactly one result row");
        return result.get(0);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> queryForMap(String sql, Object... arguments) {
        var query = entities.createNativeQuery(numberParameters(sql), Tuple.class);
        bind(query, arguments);
        Tuple tuple = (Tuple) query.getSingleResult();
        Map<String, Object> result = new LinkedHashMap<>();
        tuple.getElements().forEach(element -> result.put(element.getAlias(), tuple.get(element)));
        return result;
    }

    private void bind(jakarta.persistence.Query query, Object[] arguments) {
        for (int i = 0; i < arguments.length; i++) query.setParameter(i + 1, arguments[i]);
    }

    private String numberParameters(String sql) {
        StringBuilder result = new StringBuilder();
        int index = 1;
        for (char c : sql.toCharArray()) {
            if (c == '?') result.append('?').append(index++);
            else result.append(c);
        }
        return result.toString();
    }
}
