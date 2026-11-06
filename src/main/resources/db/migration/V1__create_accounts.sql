CREATE TABLE accounts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         TEXT        NOT NULL,
    currency     CHAR(3)     NOT NULL,
    account_type TEXT        NOT NULL DEFAULT 'CUSTOMER',
    balance      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT accounts_type_valid
        CHECK (account_type IN ('CUSTOMER', 'SYSTEM')),
    -- Customer accounts can never go negative; system accounts (e.g. funding) can.
    CONSTRAINT accounts_no_overdraft
        CHECK (account_type = 'SYSTEM' OR balance >= 0)
);
