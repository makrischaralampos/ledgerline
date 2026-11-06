package com.ledgerline.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Database-level invariants every consistent ledger must satisfy, scoped to a set of accounts. */
public final class LedgerAssertions {

    private final JdbcClient jdbc;

    public LedgerAssertions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void assertConsistent(
        Collection<UUID> accountIds,
        long expectedTotalBalance
    ) {
        assertThat(
            scalar(
                "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE id IN (:ids)",
                accountIds
            )
        )
            .as("total money across the accounts (conservation)")
            .isEqualTo(expectedTotalBalance);

        assertThat(
            scalar(
                "SELECT count(*) FROM accounts WHERE id IN (:ids) AND balance < 0",
                accountIds
            )
        )
            .as("accounts with a negative balance")
            .isZero();

        assertThat(
            scalar(
                """
                SELECT count(*) FROM accounts a
                 WHERE a.id IN (:ids)
                   AND a.balance <> COALESCE((
                         SELECT SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END)
                           FROM ledger_entries e WHERE e.account_id = a.id), 0)
                """,
                accountIds
            )
        )
            .as(
                "accounts whose balance differs from the sum of their ledger entries"
            )
            .isZero();

        assertThat(
            scalar(
                """
                SELECT count(*) FROM (
                    SELECT e.balance_after,
                           COALESCE(LAG(e.balance_after) OVER (PARTITION BY e.account_id ORDER BY e.id), 0)
                             + CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END AS expected
                      FROM ledger_entries e
                     WHERE e.account_id IN (:ids)
                ) chain
                WHERE chain.balance_after <> chain.expected
                """,
                accountIds
            )
        )
            .as(
                "ledger entries whose balance_after does not follow from the previous entry"
            )
            .isZero();

        assertThat(
            scalar(
                """
                SELECT count(*) FROM transfers t
                 WHERE (t.source_account_id IN (:ids) OR t.destination_account_id IN (:ids))
                   AND (SELECT count(*) FROM ledger_entries e WHERE e.transfer_id = t.id) <> 2
                """,
                accountIds
            )
        )
            .as("transfers that do not have exactly two ledger entries")
            .isZero();
    }

    private long scalar(String sql, Collection<UUID> ids) {
        return jdbc.sql(sql).param("ids", ids).query(Long.class).single();
    }
}
