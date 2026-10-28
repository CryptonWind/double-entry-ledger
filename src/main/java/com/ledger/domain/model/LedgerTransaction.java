package com.ledger.domain.model;

import com.ledger.domain.service.MoneyRules;
import com.ledger.domain.exception.LedgerException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Immutable domain aggregate; validates a complete posting before changing business accounts. */
public record LedgerTransaction(String id, String description, Instant recordedAt,
                                String reversesTransactionId, String currency, List<Entry> entries) {
    public LedgerTransaction { entries = List.copyOf(entries); }

    public record Posting(String accountId, Direction direction, BigDecimal amount) { }
    public record Entry(String id, String accountId, Direction direction, BigDecimal amount, BigDecimal balanceAfter) { }

    public static void validateRequest(String description, List<Posting> postings) {
        if (description == null || description.isBlank() || description.length() > 500) {
            throw LedgerException.invalid("INVALID_DESCRIPTION", "description", "Description must have 1-500 characters");
        }
        if (postings == null || postings.size() < 2 || postings.size() > 1000) {
            throw LedgerException.invalid("INVALID_ENTRY_COUNT", "entries", "A transaction needs 2-1000 entries");
        }
        for (int i = 0; i < postings.size(); i++) {
            var entry = postings.get(i);
            if (entry == null || entry.accountId() == null || entry.accountId().isBlank()
                    || entry.accountId().length() > 64 || entry.direction() == null) {
                throw LedgerException.invalid("INVALID_ENTRY", "entries[" + i + "]", "Account and direction are required");
            }
        }
    }

    public static LedgerTransaction record(String id, String description, List<Posting> postings,
            Map<String, Account> accounts, String reversesId, Instant now) {
        validateRequest(description, postings);
        validateAccountsExist(postings, accounts);
        String currency = accounts.get(postings.get(0).accountId()).getCurrency();
        Map<String, BigDecimal> deltas = calculateBalancedDeltas(postings, accounts, currency);
        validateResultingBalances(postings, accounts, deltas);
        Instant at = determineRecordedAt(accounts, now);
        List<Entry> entries = createEntries(postings, accounts, currency);
        applyPostings(accounts, deltas, at);
        return new LedgerTransaction(id, description, at, reversesId, currency, entries);
    }

    private static void validateAccountsExist(List<Posting> postings, Map<String, Account> accounts) {
        for (int i = 0; i < postings.size(); i++) {
            if (!accounts.containsKey(postings.get(i).accountId())) {
                throw LedgerException.invalid("UNKNOWN_ACCOUNT", "entries[" + i + "].accountId", "Unknown account: " + postings.get(i).accountId());
            }
        }
    }

    private static Map<String, BigDecimal> calculateBalancedDeltas(List<Posting> postings,
            Map<String, Account> accounts, String currency) {
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < postings.size(); i++) {
            var entry = postings.get(i);
            String field = "entries[" + i + "]";
            var account = accounts.get(entry.accountId());
            if (!account.getCurrency().equals(currency)) {
                throw LedgerException.invalid("MIXED_CURRENCIES", field + ".accountId", "All accounts must have the same currency");
            }
            BigDecimal amount = MoneyRules.amount(entry.amount(), currency, field + ".amount", false);
            BigDecimal delta = entry.direction() == Direction.CREDIT ? amount : amount.negate();
            total = total.add(delta);
            deltas.merge(entry.accountId(), delta, BigDecimal::add);
        }
        if (total.signum() != 0) {
            throw LedgerException.invalid("UNBALANCED_TRANSACTION", "entries", "Debits and credits must balance");
        }
        return deltas;
    }

    private static void validateResultingBalances(List<Posting> postings, Map<String, Account> accounts,
            Map<String, BigDecimal> deltas) {
        for (var account : accounts.values()) {
            int index = java.util.stream.IntStream.range(0, postings.size())
                    .filter(i -> postings.get(i).accountId().equals(account.getId())).findFirst().orElseThrow();
            account.validatePosting(deltas.get(account.getId()), "entries[" + index + "].accountId");
        }
    }

    private static Instant determineRecordedAt(Map<String, Account> accounts, Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        for (var account : accounts.values()) {
            if (account.getLastPostedAt() != null && !at.isAfter(account.getLastPostedAt())) {
                at = account.getLastPostedAt().plus(1, ChronoUnit.MICROS);
            }
        }
        return at;
    }

    private static List<Entry> createEntries(List<Posting> postings, Map<String, Account> accounts, String currency) {
        Map<String, BigDecimal> running = new HashMap<>();
        accounts.forEach((key, value) -> running.put(key, value.getBalance()));
        List<Entry> entries = new ArrayList<>();
        for (var posting : postings) {
            BigDecimal amount = posting.amount().setScale(MoneyRules.scale(currency));
            BigDecimal after = running.compute(posting.accountId(), (key, before) -> before.add(
                    posting.direction() == Direction.CREDIT ? amount : amount.negate()));
            entries.add(new Entry(UUID.randomUUID().toString(), posting.accountId(), posting.direction(), amount,
                    after.setScale(MoneyRules.scale(currency))));
        }
        return entries;
    }

    private static void applyPostings(Map<String, Account> accounts, Map<String, BigDecimal> deltas, Instant at) {
        for (var account : accounts.values()) {
            account.post(deltas.get(account.getId()), at);
        }
    }

    public List<Posting> reversalPostings() {
        if (reversesTransactionId != null) {
            throw LedgerException.invalid("REVERSAL_OF_REVERSAL", "transactionId", "A reversal cannot be reversed");
        }
        return entries.stream().map(entry -> new Posting(entry.accountId(), entry.direction().flipped(),
                entry.amount().stripTrailingZeros())).toList();
    }
}
