package com.ledger.infrastructure.output.messaging;

import com.ledger.infrastructure.output.serialization.JsonSupport;
import com.ledger.infrastructure.tracing.TraceContext;
import com.ledger.application.LedgerService;
import com.ledger.application.port.out.TransactionAnnouncements;
import com.ledger.domain.model.LedgerTransaction;
import com.ledger.infrastructure.output.persistence.OutboxEvent;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log4j2
public class OutboxAnnouncementAdapter implements TransactionAnnouncements {
    private final EntityManager entities;
    private final JsonSupport json;

    @Override public void enqueue(LedgerTransaction transaction) {
        String eventId = UUID.randomUUID().toString();
        String correlationId = TraceContext.correlationId();
        String traceparent = TraceContext.traceparent();
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", eventId);
        event.put("type", "transaction-recorded");
        event.put("version", 1);
        event.put("correlationId", correlationId);
        event.put("traceparent", traceparent);
        event.put("paymentId", MDC.get("paymentId"));
        event.put("sourceEventId", MDC.get("sourceEventId"));
        event.put("transaction", LedgerService.view(transaction));
        entities.persist(new OutboxEvent(eventId, json.write(event), transaction.recordedAt(), null, correlationId, traceparent));
        log.info("Enqueued announcement transactionId={} eventId={} paymentId={} sourceEventId={}",
                transaction.id(), eventId, MDC.get("paymentId"), MDC.get("sourceEventId"));
    }
}
