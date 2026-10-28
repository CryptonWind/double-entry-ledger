package com.ledger.infrastructure.configuration;

import com.ledger.infrastructure.input.rest.RejectReversalBody;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class RestConfiguration implements WebMvcConfigurer {
    private final RejectReversalBody interceptor;
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(interceptor); }
}
