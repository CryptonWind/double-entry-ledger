package com.ledger.infrastructure.output.messaging;

import java.nio.charset.StandardCharsets;
import com.ledger.infrastructure.tracing.TraceContext;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log4j2
public class ConfirmedPublisher {
    private final RabbitTemplate rabbit;

    public void publish(String exchange, String routingKey, String id, String payload) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setMessageId(id);
        properties.setCorrelationId(TraceContext.correlationId());
        properties.setHeader("traceparent", TraceContext.traceparent());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlation = new CorrelationData(id);
        rabbit.send(exchange, routingKey, new Message(payload.getBytes(StandardCharsets.UTF_8), properties), correlation);
        try {
            var confirmation = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirmation.ack() || correlation.getReturned() != null) {
                throw new IllegalStateException("Broker rejected or could not route event " + id);
            }
            log.debug("Broker confirmed event eventId={} exchange={} routingKey={}", id, exchange, routingKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publication interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Publication not confirmed", e);
        }
    }
}
