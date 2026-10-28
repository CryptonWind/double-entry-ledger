package com.ledger.domain.exception;

import lombok.Getter;

@Getter
public class LedgerException extends RuntimeException {
    private final int status;
    private final String code;
    private final String field;

    public LedgerException(int status, String code, String field, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public static LedgerException invalid(String code, String field, String message) {
        return new LedgerException(422, code, field, message);
    }
}
