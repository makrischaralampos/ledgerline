package com.ledgerline.account;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final RowMapper<Account> MAPPER = (rs, i) ->
        new Account(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("currency"),
            AccountType.valueOf(rs.getString("account_type")),
            rs.getLong("balance"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );

    private static final String COLUMNS =
        "id, name, currency, account_type, balance, created_at";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account insertCustomer(String name, String currency) {
        return jdbc
            .sql(
                "INSERT INTO accounts (name, currency, account_type) VALUES (:name, :currency, 'CUSTOMER') RETURNING " +
                    COLUMNS
            )
            .param("name", name)
            .param("currency", currency)
            .query(MAPPER)
            .single();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc
            .sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
            .param("id", id)
            .query(MAPPER)
            .optional();
    }

    /** Takes a row lock held until the surrounding transaction ends. */
    public Optional<Account> findByIdForUpdate(UUID id) {
        return jdbc
            .sql(
                "SELECT " + COLUMNS + " FROM accounts WHERE id = :id FOR UPDATE"
            )
            .param("id", id)
            .query(MAPPER)
            .optional();
    }

    public Optional<Account> findSystemAccount(String currency) {
        return jdbc
            .sql(
                "SELECT " +
                    COLUMNS +
                    " FROM accounts WHERE account_type = 'SYSTEM' AND currency = :currency"
            )
            .param("currency", currency)
            .query(MAPPER)
            .optional();
    }

    /** Atomically applies a signed delta and returns the new balance. Caller must hold the row lock. */
    public long adjustBalance(UUID id, long delta) {
        return jdbc
            .sql(
                "UPDATE accounts SET balance = balance + :delta WHERE id = :id RETURNING balance"
            )
            .param("delta", delta)
            .param("id", id)
            .query(Long.class)
            .single();
    }
}
