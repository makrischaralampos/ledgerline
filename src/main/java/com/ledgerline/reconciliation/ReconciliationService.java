package com.ledgerline.reconciliation;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audits the whole ledger. All queries run in one REPEATABLE READ snapshot, so the report is
 * internally consistent even while transfers are being posted concurrently.
 */
@Service
public class ReconciliationService {

    private final JdbcClient jdbc;

    public ReconciliationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport run() {
        Map<String, Long> totals = new TreeMap<>();
        jdbc.sql(
            "SELECT currency, SUM(balance) AS total FROM accounts GROUP BY currency"
        )
            .query((rs, i) ->
                Map.entry(rs.getString("currency"), rs.getLong("total"))
            )
            .list()
            .forEach(e -> totals.put(e.getKey(), e.getValue()));

        long negative = scalar(
            "SELECT count(*) FROM accounts WHERE account_type = 'CUSTOMER' AND balance < 0"
        );

        long mismatches = scalar(
            """
            SELECT count(*)
              FROM accounts a
              LEFT JOIN (SELECT account_id,
                                SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS net
                           FROM ledger_entries
                          GROUP BY account_id) l ON l.account_id = a.id
             WHERE a.balance <> COALESCE(l.net, 0)
            """
        );

        long malformed = scalar(
            """
            SELECT count(*) FROM transfers t
             WHERE (SELECT count(*) FROM ledger_entries e WHERE e.transfer_id = t.id) <> 2
            """
        );

        ReconciliationReport.Counts counts = new ReconciliationReport.Counts(
            scalar("SELECT count(*) FROM accounts"),
            scalar("SELECT count(*) FROM transfers"),
            scalar("SELECT count(*) FROM ledger_entries")
        );

        boolean consistent =
            totals
                .values()
                .stream()
                .allMatch(total -> total == 0) &&
            negative == 0 &&
            mismatches == 0 &&
            malformed == 0;

        return new ReconciliationReport(
            consistent,
            Instant.now(),
            counts,
            totals,
            negative,
            mismatches,
            malformed
        );
    }

    private long scalar(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
