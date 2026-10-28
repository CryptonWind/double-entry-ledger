ALTER TABLE accounts ADD COLUMN last_posted_at DATETIME(6) NULL;
UPDATE accounts a SET last_posted_at = (
    SELECT MAX(t.recorded_at) FROM ledger_entries e
    JOIN ledger_transactions t ON t.id = e.transaction_id WHERE e.account_id = a.id
);
ALTER TABLE outbox_events ADD COLUMN correlation_id VARCHAR(128) NOT NULL DEFAULT 'legacy';
ALTER TABLE outbox_events ADD COLUMN traceparent VARCHAR(55) NULL;
CREATE INDEX transaction_history ON ledger_transactions (recorded_at, id);
