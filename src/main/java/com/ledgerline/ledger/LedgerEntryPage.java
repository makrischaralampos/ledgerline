package com.ledgerline.ledger;

import java.util.List;

/** One page of entries, newest first. nextCursor is null on the last page. */
public record LedgerEntryPage(List<LedgerEntry> items, Long nextCursor) {}
