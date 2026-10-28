ALTER TABLE outbox_events ADD COLUMN lease_token VARCHAR(64) NULL;
ALTER TABLE outbox_events ADD COLUMN lease_until DATETIME(6) NULL;
CREATE INDEX outbox_claim ON outbox_events (published_at, lease_until, created_at);

ALTER TABLE ledger_transactions ADD COLUMN currency VARCHAR(3) NULL;
-- Explicit data migration: temporarily remove the history update guard.
DROP TRIGGER prevent_transaction_update;
UPDATE ledger_transactions t SET currency = (
    SELECT a.currency FROM ledger_entries e JOIN accounts a ON a.id = e.account_id
    WHERE e.transaction_id = t.id ORDER BY e.position LIMIT 1
);
ALTER TABLE ledger_transactions MODIFY currency VARCHAR(3) NOT NULL;
CREATE TRIGGER prevent_transaction_update
BEFORE UPDATE ON ledger_transactions FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recorded transactions cannot be updated';
