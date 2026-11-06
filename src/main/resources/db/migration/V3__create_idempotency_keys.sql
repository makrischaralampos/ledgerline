CREATE TABLE idempotency_keys (
    idempotency_key TEXT        PRIMARY KEY,
    request_hash    TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    response_status INTEGER,
    response_body   JSONB,
    transfer_id     UUID REFERENCES transfers (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,

    CONSTRAINT idempotency_status_valid CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX idx_idempotency_created_at ON idempotency_keys (created_at);
