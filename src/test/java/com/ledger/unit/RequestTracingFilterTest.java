package com.ledger.unit;

import com.ledger.infrastructure.input.rest.RequestTracingFilter;
import com.ledger.support.UnitTestBase;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

class RequestTracingFilterTest extends UnitTestBase {
    @Test
    void exposesCorrelationDuringRequestAndClearsItAfterwards() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/accounts");
        request.addHeader("X-Correlation-ID", "request-42");
        request.addHeader("traceparent", "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01");
        var response = new MockHttpServletResponse();
        new RequestTracingFilter().doFilter(request, response, (incoming, outgoing) -> {
            assertThat(MDC.get("correlationId")).isEqualTo("request-42");
            assertThat(MDC.get("traceId")).isEqualTo("1234567890abcdef1234567890abcdef");
        });
        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("request-42");
        assertThat(response.getHeader("traceparent")).startsWith("00-1234567890abcdef1234567890abcdef-");
        assertThat(MDC.get("correlationId")).isNull();
    }
}
