-- 1) The ledger is append-only: no UPDATE or DELETE, ever.
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only (% not allowed)', TG_OP
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

-- 2) Double-entry invariant: at COMMIT time, debits must equal credits per transfer.
CREATE FUNCTION assert_transfer_balanced() RETURNS trigger AS $$
DECLARE
    total_debits  BIGINT;
    total_credits BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO total_debits, total_credits
      FROM ledger_entries
     WHERE transfer_id = NEW.transfer_id;

    IF total_debits <> total_credits THEN
        RAISE EXCEPTION 'Unbalanced transfer %: debits=% credits=%',
            NEW.transfer_id, total_debits, total_credits
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transfer_balanced();
