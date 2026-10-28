package com.ledger.infrastructure.input.rest;

import com.ledger.application.port.in.AccountUseCases;
import com.ledger.application.AccountHistoryService;
import com.ledger.application.port.in.AccountHistoryUseCases;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import com.ledger.application.port.in.IdempotentOperations;
import com.ledger.application.model.LedgerModels.AccountView;
import com.ledger.application.model.LedgerModels.OpenAccount;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Account creation, current balances and immutable posting history")
@DocumentedErrors
public class AccountController {
    public record OpenAccountRequest(@jakarta.validation.constraints.NotBlank(message = "Currency is required")
                                     String currency, java.math.BigDecimal overdraftLimit) { }
    private final AccountUseCases accounts;
    private final IdempotentOperations idempotency;
    private final AccountHistoryUseCases history;

    @PostMapping
    @Operation(operationId = "openAccount", summary = "Open a zero-balance account",
            description = "Supports EUR, USD and JPY. Overdraft defaults to zero. Same key/request replays the original response.")
    @ApiResponse(responseCode = "201", description = "Account created or original result replayed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountView.class)))
    public ResponseEntity<String> open(@Parameter(description = "Required operation-scoped retry key, 1-128 ASCII letters/digits or . _ : -", required = true)
                                       @RequestHeader("Idempotency-Key") String key,
                                       @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Account currency and optional non-negative overdraft limit", required = true,
                                               content = @Content(examples = @io.swagger.v3.oas.annotations.media.ExampleObject(value = "{\"currency\":\"EUR\",\"overdraftLimit\":0}")))
                                       @Valid @RequestBody OpenAccountRequest request) {
        var command = new OpenAccount(request.currency(), request.overdraftLimit());
        var response = idempotency.execute("open-account", key, command, () -> accounts.open(command));
        return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(response.body());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getAccount", summary = "Read an account and its current balance")
    @ApiResponse(responseCode = "200", description = "Current account", content = @Content(schema = @Schema(implementation = AccountView.class)))
    public AccountView get(@PathVariable String id) {
        return accounts.get(id);
    }

    @GetMapping("/{id}/entries")
    @Operation(operationId = "getAccountEntries", summary = "Read newest-first paginated entries",
            description = "Ordered by recordedAt, transaction ID and entry position descending. Page starts at 0; size is 1-200. "
                    + "Reuse returned asOf for subsequent pages. balanceAfter is the running balance after each entry; "
                    + "overdraft applies to the atomic net effect. Timestamp pagination is not a durable snapshot token.")
    @ApiResponse(responseCode = "200", description = "Entry page", content = @Content(schema = @Schema(implementation = AccountHistoryService.EntryPage.class)))
    public AccountHistoryService.EntryPage entries(@PathVariable String id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        return history.entries(id, page, size, asOf);
    }

    @GetMapping("/{id}/balance")
    @Operation(operationId = "getHistoricalBalance", summary = "Read balance at an inclusive posting instant",
            description = "Sums committed entries whose recordedAt <= at. Returns zero before the first posting. "
                    + "Uses posting time, not the database commit visibility timeline. Supply an ISO-8601 UTC instant.")
    @ApiResponse(responseCode = "200", description = "Historical balance", content = @Content(schema = @Schema(implementation = AccountHistoryService.HistoricalBalance.class)))
    public AccountHistoryService.HistoricalBalance balance(@PathVariable String id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return history.balance(id, at);
    }
}
