package com.ledger.application.port.out;

import com.ledger.domain.model.Account;
import java.util.Optional;

public interface AccountStore {
    Optional<Account> find(String id);
    Optional<Account> lock(String id);
    Optional<String> currency(String id);
    void insert(Account account);
    void updateLocked(Account account);
}
