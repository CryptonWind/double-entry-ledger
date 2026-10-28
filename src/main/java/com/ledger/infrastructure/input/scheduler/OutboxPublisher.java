package com.ledger.infrastructure.input.scheduler;

import com.ledger.infrastructure.configuration.MessagingConfiguration;
import com.ledger.infrastructure.output.messaging.ConfirmedPublisher;
import com.ledger.infrastructure.output.persistence.OutboxEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.persistence.EntityManager;
import com.ledger.infrastructure.tracing.TraceContext;
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

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-delay:500}")
    public void publishPending() {
        try {
            // One row per DB transaction; no account locks are held while waiting for the broker.
            for (int i = 0; i < 50; i++) {
                Boolean published = transactions.execute(status -> {
                    @SuppressWarnings("unchecked")
                    List<OutboxEvent> events = entities.createNativeQuery("SELECT * FROM outbox_events WHERE published_at IS NULL "
                                    + "ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED", OutboxEvent.class).getResultList();
                    if (events.isEmpty()) {
                        return false;
                    }
                    var event = events.get(0);
                    try (var ignored = TraceContext.open(event.getCorrelationId(), TraceContext.child(event.getTraceparent()))) {
                        publisher.publish(MessagingConfiguration.TRANSACTION_EXCHANGE, "transaction.recorded", event.getId(), event.getPayload());
                        event.published();
                        log.info("Published transaction event eventId={}", event.getId());
                    }
                    return true;
                });
                if (!Boolean.TRUE.equals(published)) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Outbox publication failed; pending events will be retried: {}", e.getMessage());
        }
    }

}
