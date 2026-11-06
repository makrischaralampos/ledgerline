package com.ledgerline.transfer;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    private static final String COLUMNS =
        "id, source_account_id, destination_account_id, amount, currency, reference, created_at";

    private static final RowMapper<Transfer> MAPPER = (rs, i) ->
        new Transfer(
            rs.getObject("id", UUID.class),
            rs.getObject("source_account_id", UUID.class),
            rs.getObject("destination_account_id", UUID.class),
            rs.getLong("amount"),
            rs.getString("currency"),
            rs.getString("reference"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Transfer insert(
        UUID sourceId,
        UUID destinationId,
        long amount,
        String currency,
        String reference
    ) {
        return jdbc
            .sql(
                """
                INSERT INTO transfers (source_account_id, destination_account_id, amount, currency, reference)
                VALUES (:source, :destination, :amount, :currency, :reference)
                RETURNING\s""" + COLUMNS
            )
            .param("source", sourceId)
            .param("destination", destinationId)
            .param("amount", amount)
            .param("currency", currency)
            .param("reference", reference, Types.VARCHAR)
            .query(MAPPER)
            .single();
    }

    public Optional<Transfer> findById(UUID id) {
        return jdbc
            .sql("SELECT " + COLUMNS + " FROM transfers WHERE id = :id")
            .param("id", id)
            .query(MAPPER)
            .optional();
    }
}
