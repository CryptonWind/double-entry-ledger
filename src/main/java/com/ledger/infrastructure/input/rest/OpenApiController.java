package com.ledger.infrastructure.input.rest;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Documentation")
public class OpenApiController {
    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    @Operation(operationId = "getReferenceContract", summary = "Download the maintained reference OpenAPI contract",
            description = "For the live controller-derived contract use /v3/api-docs or /v3/api-docs.yaml.")
    public Resource contract() {
        return new ClassPathResource("openapi.yaml");
    }
}
