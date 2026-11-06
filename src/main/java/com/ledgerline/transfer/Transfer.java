package com.ledgerline.transfer;

import java.time.Instant;
import java.util.UUID;

public record Transfer(
    UUID id,
    UUID sourceAccountId,
    UUID destinationAccountId,
    long amount,
    String currency,
    String reference,
    Instant createdAt
) {}
