package com.ledger.infrastructure.input.messaging;

import com.ledger.infrastructure.configuration.MessagingConfiguration;
import com.ledger.infrastructure.output.messaging.ConfirmedPublisher;
import com.ledger.application.port.in.SettlePayment;
import com.ledger.application.PaymentService.PaymentSettled;
import com.ledger.infrastructure.output.serialization.JsonSupport;
import com.ledger.domain.exception.LedgerException;
import com.ledger.infrastructure.tracing.TraceContext;
import com.ledger.infrastructure.output.persistence.FailedPaymentMessage;
import com.ledger.infrastructure.output.persistence.DatabaseTransactions;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log4j2
public class PaymentConsumer {
    private final SettlePayment payments;
    private final JsonSupport json;
    private final EntityManager entities;
    private final DatabaseTransactions transactions;
    private final ConfirmedPublisher publisher;

    @RabbitListener(queues = MessagingConfiguration.PAYMENT_QUEUE)
    public void receive(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        Object parent = message.getMessageProperties().getHeaders().get("traceparent");
        try (var ignored = TraceContext.open(message.getMessageProperties().getCorrelationId(),
                TraceContext.child(parent instanceof String value ? value : null))) {
            try {
                var payment = json.read(payload, PaymentSettled.class);
                if (payment == null) {
                    throw LedgerException.invalid("INVALID_PAYMENT", "body", "Payment must be an object");
                }
                if (payment.paymentId() != null) org.slf4j.MDC.put("paymentId", payment.paymentId());
                if (payment.eventId() != null) org.slf4j.MDC.put("sourceEventId", payment.eventId());
                String transactionId = payments.settle(payment);
                log.info("Payment settled paymentId={} eventId={} transactionId={}",
                        payment.paymentId(), payment.eventId(), transactionId);
            } catch (LedgerException | tools.jackson.core.JacksonException e) {
                quarantine(payload, e);
            }
        }
        // Unexpected infrastructure errors escape: the original delivery remains unacknowledged.
    }

    private void quarantine(String payload, RuntimeException failure) {
        String id = UUID.randomUUID().toString();
        String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (reason.length() > 2000) {
            reason = reason.substring(0, 2000);
        }
        String failureReason = reason;
        transactions.execute(() -> {
            entities.persist(new FailedPaymentMessage(id, payload, failureReason, Instant.now()));
            return null;
        });
        // Confirm DLQ delivery before returning (and therefore before ACK of the original).
        publisher.publish(MessagingConfiguration.FAILED_EXCHANGE, "payment.failed", id,
                json.write(Map.of("failureId", id, "reason", reason, "originalPayload", payload,
                        "correlationId", TraceContext.correlationId(), "traceparent", TraceContext.traceparent())));
        log.warn("Quarantined payment failureId={} reason={}", id, reason);
    }
}
