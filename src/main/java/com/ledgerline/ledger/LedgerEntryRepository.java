package com.ledgerline.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerEntryRepository {

    private static final RowMapper<LedgerEntry> MAPPER = (rs, i) ->
        new LedgerEntry(
            rs.getLong("id"),
            rs.getObject("transfer_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            Direction.valueOf(rs.getString("direction")),
            rs.getLong("amount"),
            rs.getString("currency"),
            rs.getLong("balance_after"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );

    private final JdbcClient jdbc;

    public LedgerEntryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Keyset pagination: entries with id < before, newest first. */
    public List<LedgerEntry> findByAccount(
        UUID accountId,
        long before,
        int limit
    ) {
        return jdbc
            .sql(
                """
                SELECT id, transfer_id, account_id, direction, amount, currency, balance_after, created_at
                  FROM ledger_entries
                 WHERE account_id = :accountId AND id < :before
                 ORDER BY id DESC
                 LIMIT :limit
                """
            )
            .param("accountId", accountId)
            .param("before", before)
            .param("limit", limit)
            .query(MAPPER)
            .list();
    }
}
