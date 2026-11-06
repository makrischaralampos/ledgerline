package com.ledgerline.ledger;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
    long id,
    UUID transferId,
    UUID accountId,
    Direction direction,
    long amount,
    String currency,
    long balanceAfter,
    Instant createdAt
) {}
