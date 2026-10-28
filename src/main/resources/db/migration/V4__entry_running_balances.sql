-- Recalculate previously stored post-transaction balances as running entry balances.
-- Disable the update guard only inside this explicit schema/data migration.
DROP TRIGGER prevent_entry_update;
CREATE TEMPORARY TABLE entry_balance_backfill AS
SELECT e.id, SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
    OVER (PARTITION BY e.account_id ORDER BY t.recorded_at, t.id, e.position ROWS UNBOUNDED PRECEDING) AS running_balance
FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id;
ALTER TABLE ledger_entries MODIFY balance_after DECIMAL(28,2) NOT NULL;
UPDATE ledger_entries e JOIN entry_balance_backfill b ON b.id = e.id SET e.balance_after = b.running_balance;
DROP TEMPORARY TABLE entry_balance_backfill;
CREATE TRIGGER prevent_entry_update
BEFORE UPDATE ON ledger_entries FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recorded entries cannot be updated';
