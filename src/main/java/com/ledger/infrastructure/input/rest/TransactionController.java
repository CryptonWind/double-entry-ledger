package com.ledger.infrastructure.input.rest;

import com.ledger.application.port.in.IdempotentOperations;
import com.ledger.domain.exception.LedgerException;
import com.ledger.application.model.LedgerModels.RecordTransaction;
import com.ledger.application.model.LedgerModels.TransactionView;
import com.ledger.application.port.in.LedgerService;
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
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Atomic recording, immutable reads and single-use reversals")
@DocumentedErrors
public class TransactionController {
    private final LedgerService ledger;
    private final IdempotentOperations idempotency;

    @PostMapping
    @Operation(operationId = "recordTransaction", summary = "Record a balanced transaction atomically",
            description = "Requires 2-1000 positive entries in one currency, balanced debits/credits and valid net account balances. "
                    + "Amounts are exact; invalid precision is rejected without rounding. Same idempotency key/request replays its original result.")
    @ApiResponse(responseCode = "201", description = "Recorded transaction or original result replayed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TransactionView.class)))
    public ResponseEntity<String> record(@Parameter(description = "Required retry key; same key with different content returns 409", required = true)
                                         @RequestHeader("Idempotency-Key") String key,
                                         @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Balanced entries in one currency; use your existing account identifiers", required = true,
                                                 content = @Content(examples = @io.swagger.v3.oas.annotations.media.ExampleObject(value = "{\"description\":\"Settlement\",\"entries\":[{\"accountId\":\"settlement-EUR\",\"direction\":\"DEBIT\",\"amount\":125.50},{\"accountId\":\"MERCHANT_ACCOUNT_ID\",\"direction\":\"CREDIT\",\"amount\":125.50}]}")))
                                         @Valid @RequestBody TransactionRequests.Record request) {
        var command = request.command();
        return response(idempotency.execute("record-transaction", key, command, () -> ledger.record(command)));
    }

    @PostMapping("/{id}/reversals")
    @Operation(operationId = "reverseTransaction", summary = "Reverse an original transaction once",
            description = "Creates a new linked transaction with flipped entries. An original can be reversed once; "
                    + "a reversal cannot be reversed. Current overdraft rules apply. No request body is accepted.")
    @ApiResponse(responseCode = "201", description = "New reversal or original result replayed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TransactionView.class)))
    public ResponseEntity<String> reverse(@Parameter(description = "Original transaction identifier") @PathVariable String id,
                                          @Parameter(description = "Required retry key, scoped to the reversal operation", required = true)
                                          @RequestHeader("Idempotency-Key") String key,
                                          @Parameter(hidden = true) @RequestBody(required = false) byte[] body) {
        if (body != null && body.length > 0) {
            throw new LedgerException(400, "UNEXPECTED_BODY", "body", "Reversal requests must not include a body");
        }
        return response(idempotency.execute("reverse-transaction", key, id, () -> ledger.reverse(id)));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getTransaction", summary = "Read an immutable recorded transaction and its entries")
    @ApiResponse(responseCode = "200", description = "Recorded transaction",
            content = @Content(schema = @Schema(implementation = TransactionView.class)))
    public TransactionView get(@PathVariable String id) {
        return ledger.get(id);
    }

    private ResponseEntity<String> response(IdempotentOperations.StoredResponse result) {
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
