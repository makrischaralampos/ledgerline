package com.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class SchemaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        "postgres:16-alpine"
    );

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void balancedTransferCommits() {
        UUID a = insertAccount(100);
        UUID b = insertAccount(0);

        UUID transferId = tx.execute(status -> {
            UUID id = insertTransfer(a, b, 40);
            insertEntry(id, a, "DEBIT", 40, 60);
            insertEntry(id, b, "CREDIT", 40, 40);
            return id;
        });

        Integer entries = jdbc
            .sql("SELECT count(*) FROM ledger_entries WHERE transfer_id = :id")
            .param("id", transferId)
            .query(Integer.class)
            .single();
        assertThat(entries).isEqualTo(2);
    }

    @Test
    void unbalancedTransferIsRejectedAtCommit() {
        UUID a = insertAccount(100);
        UUID b = insertAccount(0);

        assertThatThrownBy(() ->
            tx.executeWithoutResult(status -> {
                UUID id = insertTransfer(a, b, 40);
                insertEntry(id, a, "DEBIT", 40, 60);
                // credit leg missing -> debits != credits
            })
        ).hasStackTraceContaining("Unbalanced transfer");
    }

    @Test
    void ledgerIsAppendOnly() {
        UUID a = insertAccount(100);
        UUID b = insertAccount(0);
        tx.executeWithoutResult(status -> {
            UUID id = insertTransfer(a, b, 10);
            insertEntry(id, a, "DEBIT", 10, 90);
            insertEntry(id, b, "CREDIT", 10, 10);
        });

        assertThatThrownBy(() ->
            jdbc.sql("UPDATE ledger_entries SET amount = 1").update()
        ).hasStackTraceContaining("append-only");
        assertThatThrownBy(() ->
            jdbc.sql("DELETE FROM ledger_entries").update()
        ).hasStackTraceContaining("append-only");
    }

    @Test
    void customerAccountCannotGoNegative() {
        UUID a = insertAccount(10);

        assertThatThrownBy(() ->
            jdbc
                .sql("UPDATE accounts SET balance = -1 WHERE id = :id")
                .param("id", a)
                .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- helpers ----

    private UUID insertAccount(long balance) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
            """
            INSERT INTO accounts (id, name, currency, account_type, balance)
            VALUES (:id, 'test', 'EUR', 'CUSTOMER', :balance)
            """
        )
            .param("id", id)
            .param("balance", balance)
            .update();
        return id;
    }

    private UUID insertTransfer(UUID from, UUID to, long amount) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
            """
            INSERT INTO transfers (id, source_account_id, destination_account_id, amount, currency)
            VALUES (:id, :from, :to, :amount, 'EUR')
            """
        )
            .param("id", id)
            .param("from", from)
            .param("to", to)
            .param("amount", amount)
            .update();
        return id;
    }

    private void insertEntry(
        UUID transferId,
        UUID accountId,
        String direction,
        long amount,
        long balanceAfter
    ) {
        jdbc.sql(
            """
            INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, currency, balance_after)
            VALUES (:t, :a, :d, :amount, 'EUR', :bal)
            """
        )
            .param("t", transferId)
            .param("a", accountId)
            .param("d", direction)
            .param("amount", amount)
            .param("bal", balanceAfter)
            .update();
    }
}
