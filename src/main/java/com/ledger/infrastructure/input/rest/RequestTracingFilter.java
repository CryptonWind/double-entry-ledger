package com.ledger.infrastructure.input.rest;

import com.ledger.infrastructure.tracing.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Log4j2
public class RequestTracingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (var ignored = TraceContext.open(request.getHeader("X-Correlation-ID"),
                TraceContext.child(request.getHeader("traceparent")))) {
            response.setHeader("X-Correlation-ID", TraceContext.correlationId());
            response.setHeader("traceparent", TraceContext.traceparent());
            long start = System.nanoTime();
            try {
                chain.doFilter(request, response);
            } finally {
                log.info("HTTP method={} path={} status={} durationMs={}", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            }
        }
    }
}
