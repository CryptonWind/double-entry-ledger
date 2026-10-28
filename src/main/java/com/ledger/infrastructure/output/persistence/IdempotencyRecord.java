package com.ledger.infrastructure.output.persistence;

import jakarta.persistence.*;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "idempotency_records")
@IdClass(IdempotencyRecord.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {
    @Id @Column(length = 100) private String scope;
    @Id @Column(length = 128) private String requestKey;
    @Column(nullable = false, length = 64, columnDefinition = "char(64)") private String fingerprint;
    private Integer responseStatus;
    @Column(columnDefinition = "longtext") private String responseBody;

    public void complete(int status, String body) {
        responseStatus = status;
        responseBody = body;
    }

    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String scope;
        private String requestKey;
    }
}
