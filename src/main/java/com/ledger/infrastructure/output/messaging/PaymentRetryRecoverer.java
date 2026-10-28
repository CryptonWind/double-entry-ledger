package com.ledger.infrastructure.output.messaging;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

/** Confirm durable retry/parking publication before allowing ACK of the original delivery. */
@Component
@RequiredArgsConstructor
@Log4j2
public class PaymentRetryRecoverer implements MessageRecoverer {
    private final RabbitTemplate rabbit;
    @org.springframework.beans.factory.annotation.Value("${ledger.payments.delayed-retries:3}")
    private int delayedRetries = 3;
    private io.micrometer.core.instrument.MeterRegistry metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public void metrics(io.micrometer.core.instrument.MeterRegistry registry) { metrics = registry; }
    @Override public void recover(Message message, Throwable cause) {
        // Do not mutate the original delivery if publication fails and it has to be requeued.
        Message recoveryMessage = org.springframework.amqp.core.MessageBuilder.fromClonedMessage(message).build();
        Object header = message.getMessageProperties().getHeaders().get("ledger-retry-count");
        int attempts = header instanceof Number number ? number.intValue() : 0;
        int limit = Math.max(0, Math.min(3, delayedRetries));
        String routing = attempts >= limit ? "parked" : "retry." + (attempts + 1);
        recoveryMessage.getMessageProperties().setHeader("ledger-retry-count", attempts + 1);
        recoveryMessage.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        var confirmation = new CorrelationData(java.util.UUID.randomUUID().toString());
        try {
            rabbit.send("ledger.payment-recovery", routing, recoveryMessage, confirmation);
            if (!confirmation.getFuture().get(10, TimeUnit.SECONDS).ack() || confirmation.getReturned() != null) {
                throw new IllegalStateException("Recovery publication was rejected or unroutable");
            }
            log.warn("Payment infrastructure failure routed={} attempt={} messageId={}", routing, attempts + 1,
                    message.getMessageProperties().getMessageId());
            if (metrics != null) metrics.counter("ledger.payments.recovery", "destination", routing).increment();
        } catch (Exception e) {
            if (metrics != null) metrics.counter("ledger.payments.recovery.publication.failures").increment();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ImmediateRequeueAmqpException("Broker recovery publication unavailable; retain original payment", e);
        }
    }
}
