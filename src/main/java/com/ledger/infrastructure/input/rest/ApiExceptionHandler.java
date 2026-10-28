package com.ledger.infrastructure.input.rest;

import com.ledger.application.model.LedgerModels.ApiError;
import com.ledger.domain.exception.LedgerException;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
@Log4j2
public class ApiExceptionHandler {
    @ExceptionHandler(tools.jackson.core.JacksonException.class)
    ResponseEntity<ApiError> internalSerialization(tools.jackson.core.JacksonException e) {
        log.error("Internal JSON serialization failure", e);
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_SERIALIZATION_ERROR", null,
                "The server could not produce the response"));
    }
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<ApiError> constraint(jakarta.validation.ConstraintViolationException e) {
        var violation = e.getConstraintViolations().iterator().next();
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", violation.getPropertyPath().toString(), violation.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    ResponseEntity<ApiError> methodValidation(org.springframework.web.method.annotation.HandlerMethodValidationException e) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "request", "Request parameters violate the API contract"));
    }
    @ExceptionHandler(LedgerException.class)
    ResponseEntity<ApiError> domain(LedgerException e) {
        log.warn("Request refused code={} field={} status={} reason={}", e.getCode(), e.getField(), e.getStatus(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getCode(), e.getField(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        var error = e.getBindingResult().getFieldErrors().get(0);
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", error.getField(), error.getDefaultMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest().body(new ApiError("MISSING_HEADER", e.getHeaderName(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformed(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_JSON", "body", "Malformed JSON or invalid field type"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidParameter(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_PARAMETER", e.getName(), "Invalid parameter value or format"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(new ApiError("MISSING_PARAMETER", e.getParameterName(), "Required parameter is missing"));
    }

    @ExceptionHandler(TransientDataAccessException.class)
    ResponseEntity<ApiError> unavailable(TransientDataAccessException e) {
        log.warn("Database operation exhausted retry attempts", e);
        return ResponseEntity.status(503).body(new ApiError("TEMPORARILY_UNAVAILABLE", null, "Retry with the same Idempotency-Key"));
    }
}
