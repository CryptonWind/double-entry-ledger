package com.ledger.domain.model;

public enum Direction {
    DEBIT, CREDIT;

    public Direction flipped() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
