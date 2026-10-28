package com.ledger.application.port.out;

import com.ledger.domain.model.Account;
import java.util.Optional;

public interface AccountStore {
    Optional<Account> find(String id);
    /** Acquire the account write lock and return fresh business state; caller owns the transaction. */
    Optional<Account> lock(String id);
    Optional<String> currency(String id);
    void insert(Account account);
    /** Store a validated domain state for an account already locked in this transaction. */
    void updateLocked(Account account);
}
