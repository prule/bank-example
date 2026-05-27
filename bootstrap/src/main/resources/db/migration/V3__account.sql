-- Drop foreign key so we can modify account PK
ALTER TABLE ledger_movement DROP CONSTRAINT fk_movement_account;

-- Modify account primary key and columns
ALTER TABLE account DROP PRIMARY KEY;
ALTER TABLE account ADD PRIMARY KEY (id);

-- Alter columns to match specs
ALTER TABLE account ALTER COLUMN account_number VARCHAR(64) NOT NULL;
ALTER TABLE account ADD CONSTRAINT uc_account_number UNIQUE (account_number);

ALTER TABLE account ALTER COLUMN balance NUMERIC(19, 2) NOT NULL;
ALTER TABLE account ADD CONSTRAINT chk_account_balance CHECK (balance >= 0);

ALTER TABLE account ALTER COLUMN status VARCHAR(16) NOT NULL;
ALTER TABLE account ADD CONSTRAINT chk_account_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'));

-- Re-create unique index on account_number
CREATE UNIQUE INDEX IF NOT EXISTS idx_account_account_number ON account (account_number);

-- Restore foreign key constraint
ALTER TABLE ledger_movement ADD CONSTRAINT fk_movement_account FOREIGN KEY (account_number) REFERENCES account(account_number);
