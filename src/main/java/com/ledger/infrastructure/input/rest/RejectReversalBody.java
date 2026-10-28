package com.ledger.infrastructure.input.rest;

import com.ledger.domain.exception.LedgerException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RejectReversalBody implements HandlerInterceptor {
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (handler instanceof org.springframework.web.method.HandlerMethod method
                && method.hasMethodAnnotation(NoRequestBody.class)) {
            if (request.getInputStream().read() != -1) {
                throw new LedgerException(400, "UNEXPECTED_BODY", "body", "Reversal requests must not include a body");
            }
        }
        return true;
    }
}
