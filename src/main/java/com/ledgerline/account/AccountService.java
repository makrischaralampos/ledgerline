package com.ledgerline.account;

import com.ledgerline.ledger.LedgerEntry;
import com.ledgerline.ledger.LedgerEntryPage;
import com.ledgerline.ledger.LedgerEntryRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    static final Set<String> SUPPORTED_CURRENCIES = Set.of("EUR", "USD", "GBP");
    static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accounts;
    private final LedgerEntryRepository ledgerEntries;

    public AccountService(
        AccountRepository accounts,
        LedgerEntryRepository ledgerEntries
    ) {
        this.accounts = accounts;
        this.ledgerEntries = ledgerEntries;
    }

    @Transactional
    public Account create(String name, String currency) {
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new UnsupportedCurrencyException(currency);
        }
        return accounts.insertCustomer(name.strip(), currency);
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts
            .findById(id)
            .orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public LedgerEntryPage entries(UUID accountId, Long cursor, int limit) {
        get(accountId); // 404 if the account does not exist
        int size = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        long before = cursor == null ? Long.MAX_VALUE : cursor;

        List<LedgerEntry> rows = ledgerEntries.findByAccount(
            accountId,
            before,
            size + 1
        );
        boolean hasMore = rows.size() > size;
        List<LedgerEntry> items = hasMore ? rows.subList(0, size) : rows;
        Long next = hasMore ? items.get(items.size() - 1).id() : null;
        return new LedgerEntryPage(items, next);
    }
}
