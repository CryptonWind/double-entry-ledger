package com.ledger.application.model;

import com.ledger.domain.model.Direction;
import java.math.BigDecimal;
import java.time.Instant;

public record HistoryEntry(String id, String transactionId, Instant recordedAt, int position,
                           Direction direction, BigDecimal amount, BigDecimal balanceAfter) { }
