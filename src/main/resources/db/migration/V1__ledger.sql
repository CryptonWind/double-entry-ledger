CREATE TABLE accounts (
    id VARCHAR(64) PRIMARY KEY,
    currency VARCHAR(3) NOT NULL,
    overdraft_limit DECIMAL(24,2) NULL,
    balance DECIMAL(24,2) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT account_overdraft CHECK (overdraft_limit IS NULL OR overdraft_limit >= 0),
    CONSTRAINT account_balance CHECK (overdraft_limit IS NULL OR balance >= -overdraft_limit)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE ledger_transactions (
    id VARCHAR(64) PRIMARY KEY,
    description VARCHAR(500) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    reverses_transaction_id VARCHAR(64) NULL,
    CONSTRAINT unique_reversal UNIQUE (reverses_transaction_id),
    CONSTRAINT reversal_original FOREIGN KEY (reverses_transaction_id) REFERENCES ledger_transactions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE ledger_entries (
    id VARCHAR(64) PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    position INT NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    direction VARCHAR(6) NOT NULL,
    amount DECIMAL(24,2) NOT NULL,
    balance_after DECIMAL(24,2) NOT NULL,
    CONSTRAINT entry_transaction FOREIGN KEY (transaction_id) REFERENCES ledger_transactions(id),
    CONSTRAINT entry_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT entry_position UNIQUE (transaction_id, position),
    CONSTRAINT positive_amount CHECK (amount > 0),
    CONSTRAINT entry_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    INDEX account_entries (account_id, transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE idempotency_records (
    scope VARCHAR(100) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    response_status INT NULL,
    response_body LONGTEXT NULL,
    PRIMARY KEY (scope, request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE processed_payments (
    payment_id VARCHAR(128) PRIMARY KEY,
    fingerprint CHAR(64) NOT NULL,
    transaction_id VARCHAR(64) NULL,
    CONSTRAINT payment_transaction FOREIGN KEY (transaction_id) REFERENCES ledger_transactions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    payload LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    INDEX pending_events (published_at, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE failed_payment_messages (
    id VARCHAR(64) PRIMARY KEY,
    payload LONGTEXT NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    failed_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO accounts (id, currency, overdraft_limit, balance, created_at) VALUES
    ('settlement-EUR', 'EUR', NULL, 0, UTC_TIMESTAMP(6)),
    ('settlement-USD', 'USD', NULL, 0, UTC_TIMESTAMP(6)),
    ('settlement-JPY', 'JPY', NULL, 0, UTC_TIMESTAMP(6));
