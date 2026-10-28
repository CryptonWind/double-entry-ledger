package com.ledger.infrastructure.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean OpenAPI ledgerOpenApi() {
        return new OpenAPI().info(new Info().title("Double-entry Ledger API").version("1.0.0")
                .description("Exact, immutable double-entry accounting. POST operations require a persistent, operation-scoped "
                        + "Idempotency-Key. Retries replay the original status and body; different content conflicts. "
                        + "Use decimal-safe clients for monetary numbers. Currency precision: EUR/USD 2, JPY 0."));
    }

    @Bean OpenApiCustomizer correlationHeaders() {
        return api -> api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
            if (operation.getParameters() == null || operation.getParameters().stream().noneMatch(p -> "X-Correlation-ID".equals(p.getName())))
                operation.addParametersItem(new HeaderParameter().name("X-Correlation-ID")
                    .description("Optional correlation ID; invalid/missing values are replaced and returned in the response")
                    .schema(new StringSchema().pattern("^[A-Za-z0-9._:-]{1,128}$")));
            if (operation.getParameters() == null || operation.getParameters().stream().noneMatch(p -> "traceparent".equals(p.getName())))
                operation.addParametersItem(new HeaderParameter().name("traceparent")
                    .description("Optional W3C version-00 trace context; propagated through messages and announcements")
                    .schema(new StringSchema()));
        }));
    }
}
