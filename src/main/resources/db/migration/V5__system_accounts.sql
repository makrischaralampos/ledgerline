-- Currency must look like an ISO 4217 code.
ALTER TABLE accounts
    ADD CONSTRAINT accounts_currency_format CHECK (currency ~ '^[A-Z]{3}$');

-- Exactly one system (funding) account per currency.
CREATE UNIQUE INDEX uq_system_account_per_currency
    ON accounts (currency) WHERE account_type = 'SYSTEM';

-- Funding accounts are the counterparty for deposits. Their balance goes
-- negative as money enters the system, so total money across all accounts
-- always sums to zero.
INSERT INTO accounts (name, currency, account_type, balance) VALUES
    ('Funding EUR', 'EUR', 'SYSTEM', 0),
    ('Funding USD', 'USD', 'SYSTEM', 0),
    ('Funding GBP', 'GBP', 'SYSTEM', 0);
