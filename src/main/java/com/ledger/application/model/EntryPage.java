package com.ledger.application.model;

import java.time.Instant;
import java.util.List;

public record EntryPage(List<HistoryEntry> content, int page, int size, long totalElements, long totalPages, Instant asOf) {
    public EntryPage { content = List.copyOf(content); }
}
