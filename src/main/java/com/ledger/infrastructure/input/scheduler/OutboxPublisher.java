package com.ledger.infrastructure.input.scheduler;

import com.ledger.infrastructure.configuration.MessagingConfiguration;
import com.ledger.infrastructure.output.messaging.ConfirmedPublisher;
import com.ledger.infrastructure.output.persistence.OutboxEvent;
import com.ledger.infrastructure.tracing.TraceContext;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Log4j2
@ConditionalOnProperty(name = "ledger.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private final EntityManager entities;
    private final TransactionTemplate transactions;
    private final ConfirmedPublisher publisher;

    private record Claim(String id, String payload, String correlationId, String traceparent, String token) { }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-delay:500}")
    public void publishPending() {
        for (int i = 0; i < 50; i++) {
            Claim claim;
            try {
                claim = claim();
                if (claim == null) return;
            } catch (RuntimeException e) {
                log.warn("Outbox claim failed; retrying on next poll", e);
                return;
            }
            try (var ignored = TraceContext.open(claim.correlationId(), TraceContext.child(claim.traceparent()))) {
                // No database transaction or pooled connection is held while waiting for RabbitMQ.
                publisher.publish(MessagingConfiguration.TRANSACTION_EXCHANGE, "transaction.recorded", claim.id(), claim.payload());
                Integer updated = transactions.execute(status -> entities.createNativeQuery(
                        "UPDATE outbox_events SET published_at=UTC_TIMESTAMP(6), lease_token=NULL, lease_until=NULL "
                                + "WHERE id=:id AND lease_token=:token AND published_at IS NULL")
                        .setParameter("id", claim.id()).setParameter("token", claim.token()).executeUpdate());
                if (updated != null && updated == 1) {
                    log.info("Published transaction event eventId={}", claim.id());
                } else {
                    log.warn("Outbox lease lost after broker confirmation eventId={}; duplicate delivery possible", claim.id());
                }
            } catch (RuntimeException e) {
                // Leave the lease intact: failed publications back off and crashed workers recover identically.
                log.warn("Outbox publication failed eventId={}; eligible again after lease expiry", claim.id(), e);
            }
        }
    }

    private Claim claim() {
        return transactions.execute(status -> {
            @SuppressWarnings("unchecked")
            List<OutboxEvent> events = entities.createNativeQuery("SELECT * FROM outbox_events "
                    + "WHERE published_at IS NULL AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6)) "
                    + "ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED", OutboxEvent.class).getResultList();
            if (events.isEmpty()) return null;
            var event = events.get(0);
            String token = UUID.randomUUID().toString();
            entities.createNativeQuery("UPDATE outbox_events SET lease_token=:token, "
                    + "lease_until=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) WHERE id=:id")
                    .setParameter("token", token).setParameter("id", event.getId()).executeUpdate();
            return new Claim(event.getId(), event.getPayload(), event.getCorrelationId(), event.getTraceparent(), token);
        });
    }
}
