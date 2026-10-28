package com.ledger.unit;

import com.ledger.infrastructure.output.messaging.PaymentRetryRecoverer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class PaymentRetryRecovererTest {
    @Test void delaysThenParksAndNeverAcknowledgesUnconfirmedRecovery() {
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(call -> { CorrelationData data = call.getArgument(3); data.getFuture().complete(new CorrelationData.Confirm(true, null)); return null; })
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var recoverer = new PaymentRetryRecoverer(rabbit);
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        recoverer.metrics(metrics);
        var message = new Message(new byte[0], new MessageProperties());
        recoverer.recover(message, new IllegalStateException("DB unavailable"));
        verify(rabbit).send(eq("ledger.payment-recovery"), eq("retry.1"), any(Message.class), any(CorrelationData.class));
        assertThat(message.getMessageProperties().getHeaders()).doesNotContainKey("ledger-retry-count");
        message.getMessageProperties().setHeader("ledger-retry-count", 3);
        recoverer.recover(message, new IllegalStateException("DB unavailable"));
        verify(rabbit).send(eq("ledger.payment-recovery"), eq("parked"), any(Message.class), any(CorrelationData.class));
        assertThat(metrics.get("ledger.payments.recovery").tag("destination", "retry.1").counter().count()).isEqualTo(1);
        assertThat(metrics.get("ledger.payments.recovery").tag("destination", "parked").counter().count()).isEqualTo(1);
        reset(rabbit);
        doThrow(new IllegalStateException("Broker unavailable")).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> recoverer.recover(message, new IllegalStateException()))
                .isInstanceOf(org.springframework.amqp.ImmediateRequeueAmqpException.class);
        assertThat(metrics.get("ledger.payments.recovery.publication.failures").counter().count()).isEqualTo(1);
    }
}
