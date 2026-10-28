package com.ledger.infrastructure.tracing;

import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/** Request-scoped correlation, including a W3C trace context propagated over HTTP and AMQP. */
public final class TraceContext implements AutoCloseable {
    private final Map<String, String> previous;

    private TraceContext(String correlationId, String traceparent) {
        previous = MDC.getCopyOfContextMap();
        MDC.clear();
        MDC.put("correlationId", safe(correlationId));
        String parent = validParent(traceparent) ? traceparent : newParent();
        MDC.put("traceparent", parent);
        MDC.put("traceId", parent.substring(3, 35));
        MDC.put("spanId", parent.substring(36, 52));
    }

    public static TraceContext open(String correlationId, String traceparent) {
        return new TraceContext(correlationId, traceparent);
    }

    public static String correlationId() {
        String value = MDC.get("correlationId");
        return value == null ? UUID.randomUUID().toString() : value;
    }

    public static String traceparent() {
        String value = MDC.get("traceparent");
        return value == null ? newParent() : value;
    }

    public static String child(String parent) {
        if (!validParent(parent)) return newParent();
        return "00-" + parent.substring(3, 35) + "-" + randomHex().substring(0, 16) + "-" + parent.substring(53);
    }

    private static boolean validParent(String value) {
        return value != null && value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                && !value.substring(3, 35).equals("0".repeat(32))
                && !value.substring(36, 52).equals("0".repeat(16));
    }

    private static String safe(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : UUID.randomUUID().toString();
    }

    private static String randomHex() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String newParent() { return "00-" + randomHex() + "-" + randomHex().substring(0, 16) + "-01"; }

    @Override
    public void close() {
        MDC.clear();
        if (previous != null) MDC.setContextMap(previous);
    }
}
