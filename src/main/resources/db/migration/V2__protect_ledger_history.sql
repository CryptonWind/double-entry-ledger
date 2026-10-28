CREATE TRIGGER prevent_transaction_update
BEFORE UPDATE ON ledger_transactions FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recorded transactions cannot be updated';

CREATE TRIGGER prevent_transaction_delete
BEFORE DELETE ON ledger_transactions FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recorded transactions cannot be deleted';

CREATE TRIGGER prevent_entry_update
BEFORE UPDATE ON ledger_entries FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recorded entries cannot be updated';

CREATE TRIGGER prevent_entry_delete
BEFORE DELETE ON ledger_entries FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recorded entries cannot be deleted';
