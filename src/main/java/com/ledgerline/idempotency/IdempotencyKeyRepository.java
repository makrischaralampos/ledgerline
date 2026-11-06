package com.ledgerline.idempotency;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyKeyRepository {

    public record StoredKey(
        String requestHash,
        String status,
        Integer responseStatus,
        String responseBody
    ) {}

    private static final RowMapper<StoredKey> MAPPER = (rs, i) ->
        new StoredKey(
            rs.getString("request_hash"),
            rs.getString("status"),
            rs.getObject("response_status", Integer.class),
            rs.getString("response_body")
        );

    private final JdbcClient jdbc;

    public IdempotencyKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tries to claim the key. If another transaction is mid-flight on the same key, this call blocks
     * until that transaction commits (returns false) or rolls back (returns true).
     */
    public boolean tryClaim(String key, String requestHash) {
        return (
            jdbc
                .sql(
                    """
                    INSERT INTO idempotency_keys (idempotency_key, request_hash, status)
                    VALUES (:key, :hash, 'IN_PROGRESS')
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """
                )
                .param("key", key)
                .param("hash", requestHash)
                .update() == 1
        );
    }

    public Optional<StoredKey> find(String key) {
        return jdbc
            .sql(
                """
                SELECT request_hash, status, response_status, CAST(response_body AS text) AS response_body
                  FROM idempotency_keys
                 WHERE idempotency_key = :key
                """
            )
            .param("key", key)
            .query(MAPPER)
            .optional();
    }

    public void complete(String key, int responseStatus, String responseBody) {
        jdbc.sql(
            """
            UPDATE idempotency_keys
               SET status = 'COMPLETED',
                   response_status = :status,
                   response_body = CAST(:body AS jsonb),
                   completed_at = now()
             WHERE idempotency_key = :key
            """
        )
            .param("key", key)
            .param("status", responseStatus)
            .param("body", responseBody)
            .update();
    }

    public int deleteCreatedBefore(OffsetDateTime cutoff) {
        return jdbc
            .sql("DELETE FROM idempotency_keys WHERE created_at < :cutoff")
            .param("cutoff", cutoff)
            .update();
    }
}
