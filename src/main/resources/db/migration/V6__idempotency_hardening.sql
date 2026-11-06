-- The response is replayed from the stored body, so the transfer FK is not needed.
ALTER TABLE idempotency_keys DROP COLUMN transfer_id;

-- A completed key must carry everything needed to replay its response.
ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_completed_has_response
        CHECK (status <> 'COMPLETED'
               OR (response_status IS NOT NULL AND response_body IS NOT NULL AND completed_at IS NOT NULL));
