package com.ledgerline.account;

import java.time.Instant;
import java.util.UUID;

/** Balance is in minor units (e.g. cents). */
public record Account(
    UUID id,
    String name,
    String currency,
    AccountType type,
    long balance,
    Instant createdAt
) {}
