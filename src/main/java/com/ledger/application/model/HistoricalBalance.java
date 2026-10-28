package com.ledger.application.model;

import java.math.BigDecimal;
import java.time.Instant;

public record HistoricalBalance(String accountId, String currency, Instant at, BigDecimal balance) { }
