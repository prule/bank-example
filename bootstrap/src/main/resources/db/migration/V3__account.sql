-- F05 — account persistence
-- Mutable aggregate row: balance and status change in place as transfers
-- commit. The ledger (V2) is the append-only history; this table is the
-- fast cached view consumed by GET /api/v1/accounts/{accountNumber} and by
-- future fund-transfer/opening flows.

CREATE TABLE account (
    id              UUID            NOT NULL PRIMARY KEY,
    account_number  VARCHAR(64)     NOT NULL,
    balance         NUMERIC(19, 2)  NOT NULL,
    status          VARCHAR(16)     NOT NULL,
    CONSTRAINT balance_non_negative CHECK (balance >= 0),
    CONSTRAINT account_status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE UNIQUE INDEX idx_account_account_number ON account(account_number);
