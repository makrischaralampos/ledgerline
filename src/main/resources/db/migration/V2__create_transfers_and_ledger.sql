CREATE TABLE transfers (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id      UUID        NOT NULL REFERENCES accounts (id),
    destination_account_id UUID        NOT NULL REFERENCES accounts (id),
    amount                 BIGINT      NOT NULL,
    currency               CHAR(3)     NOT NULL,
    reference              TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT transfers_distinct_accounts CHECK (source_account_id <> destination_account_id)
);

CREATE INDEX idx_transfers_source ON transfers (source_account_id, created_at DESC);
CREATE INDEX idx_transfers_destination ON transfers (destination_account_id, created_at DESC);

CREATE TABLE ledger_entries (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id   UUID        NOT NULL REFERENCES transfers (id),
    account_id    UUID        NOT NULL REFERENCES accounts (id),
    direction     TEXT        NOT NULL,
    amount        BIGINT      NOT NULL,
    currency      CHAR(3)     NOT NULL,
    balance_after BIGINT      NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ledger_entries_direction_valid CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_amount_positive CHECK (amount > 0),
    -- Exactly one debit and one credit per transfer.
    CONSTRAINT ledger_entries_one_per_direction UNIQUE (transfer_id, direction)
);

CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id, id DESC);
