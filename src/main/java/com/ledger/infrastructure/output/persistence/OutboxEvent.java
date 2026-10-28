package com.ledger.infrastructure.output.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "outbox_events")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {
    @Id @Column(length = 64) private String id;
    @Column(nullable = false, columnDefinition = "longtext") private String payload;
    @Column(nullable = false) private Instant createdAt;
    private Instant publishedAt;
    @Column(nullable = false, length = 128) private String correlationId;
    @Column(length = 55) private String traceparent;

    public void published() { publishedAt = Instant.now(); }
}
