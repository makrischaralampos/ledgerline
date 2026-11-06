package com.ledgerline.reconciliation;

import java.time.Instant;
import java.util.Map;

public record ReconciliationReport(
    boolean consistent,
    Instant checkedAt,
    Counts counts,
    /** Sum of all balances per currency, system accounts included. Must be 0 for every currency. */
    Map<String, Long> balanceTotalsByCurrency,
    long negativeCustomerBalances,
    long balanceLedgerMismatches,
    long malformedTransfers
) {
    public record Counts(long accounts, long transfers, long ledgerEntries) {}
}
