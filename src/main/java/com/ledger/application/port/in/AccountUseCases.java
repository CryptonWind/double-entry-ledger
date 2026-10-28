package com.ledger.application.port.in;

import com.ledger.application.model.LedgerModels.AccountView;
import com.ledger.application.model.LedgerModels.OpenAccount;

public interface AccountUseCases {
    AccountView open(OpenAccount request);
    AccountView get(String id);
}
