-- MySQL DDL cannot roll back. Reinstate guards if a backfill migration fails.
-- Callbacks are not versioned migrations; V4/V5 checksums remain unchanged.
DROP TRIGGER IF EXISTS prevent_transaction_update;
CREATE TRIGGER prevent_transaction_update
BEFORE UPDATE ON ledger_transactions FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recorded transactions cannot be updated';
DROP TRIGGER IF EXISTS prevent_transaction_delete;
CREATE TRIGGER prevent_transaction_delete
BEFORE DELETE ON ledger_transactions FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recorded transactions cannot be deleted';
DROP TRIGGER IF EXISTS prevent_entry_update;
CREATE TRIGGER prevent_entry_update
BEFORE UPDATE ON ledger_entries FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recorded entries cannot be updated';
DROP TRIGGER IF EXISTS prevent_entry_delete;
CREATE TRIGGER prevent_entry_delete
BEFORE DELETE ON ledger_entries FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recorded entries cannot be deleted';
