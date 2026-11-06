package com.ledgerline.transfer;

import com.ledgerline.account.Account;
import com.ledgerline.account.AccountNotFoundException;
import com.ledgerline.account.AccountRepository;
import com.ledgerline.account.AccountType;
import com.ledgerline.account.UnsupportedCurrencyException;
import com.ledgerline.ledger.Direction;
import com.ledgerline.ledger.LedgerEntryRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves money between accounts using double-entry bookkeeping.
 *
 * <p>Concurrency strategy: pessimistic row locks acquired in a canonical (sorted id) order, so two
 * transfers touching the same accounts can never deadlock. Balances are validated only after the
 * locks are held. Every public method joins the caller's transaction (REQUIRED), which lets the
 * idempotency layer wrap a transfer in a single atomic unit.
 */
@Service
public class TransferService {

    private enum Kind {
        TRANSFER,
        DEPOSIT,
    }

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledger;

    public TransferService(
        AccountRepository accounts,
        TransferRepository transfers,
        LedgerEntryRepository ledger
    ) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
    }

    /** Customer-to-customer transfer. */
    @Transactional
    public Transfer transfer(
        UUID sourceId,
        UUID destinationId,
        long amount,
        String currency,
        String reference
    ) {
        return post(
            Kind.TRANSFER,
            sourceId,
            destinationId,
            amount,
            currency,
            reference
        );
    }

    /** Money entering the system: funding account for the currency -> customer account. */
    @Transactional
    public Transfer deposit(
        UUID accountId,
        long amount,
        String currency,
        String reference
    ) {
        Account funding = accounts
            .findSystemAccount(currency)
            .orElseThrow(() -> new UnsupportedCurrencyException(currency));
        return post(
            Kind.DEPOSIT,
            funding.id(),
            accountId,
            amount,
            currency,
            reference
        );
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID id) {
        return transfers
            .findById(id)
            .orElseThrow(() -> new TransferNotFoundException(id));
    }

    private Transfer post(
        Kind kind,
        UUID sourceId,
        UUID destinationId,
        long amount,
        String currency,
        String reference
    ) {
        if (sourceId.equals(destinationId)) {
            throw new InvalidTransferException(
                "SAME_ACCOUNT",
                "Source and destination accounts must differ"
            );
        }

        // 1. Lock both accounts in canonical order, then 2. validate against the locked state.
        Map<UUID, Account> locked = lockInOrder(sourceId, destinationId);
        Account source = locked.get(sourceId);
        Account destination = locked.get(destinationId);

        validate(kind, source, destination, amount, currency);

        // 3. Post: transfer row, balance updates, and the two ledger entries.
        Transfer transfer = transfers.insert(
            sourceId,
            destinationId,
            amount,
            currency,
            reference
        );
        long sourceBalanceAfter = accounts.adjustBalance(sourceId, -amount);
        long destinationBalanceAfter = accounts.adjustBalance(
            destinationId,
            amount
        );
        ledger.insert(
            transfer.id(),
            sourceId,
            Direction.DEBIT,
            amount,
            currency,
            sourceBalanceAfter
        );
        ledger.insert(
            transfer.id(),
            destinationId,
            Direction.CREDIT,
            amount,
            currency,
            destinationBalanceAfter
        );
        return transfer;
    }

    private Map<UUID, Account> lockInOrder(UUID a, UUID b) {
        Map<UUID, Account> locked = new HashMap<>();
        for (UUID id : Stream.of(a, b).sorted().toList()) {
            locked.put(
                id,
                accounts
                    .findByIdForUpdate(id)
                    .orElseThrow(() -> new AccountNotFoundException(id))
            );
        }
        return locked;
    }

    private void validate(
        Kind kind,
        Account source,
        Account destination,
        long amount,
        String currency
    ) {
        AccountType expectedSource =
            kind == Kind.DEPOSIT ? AccountType.SYSTEM : AccountType.CUSTOMER;
        if (
            source.type() != expectedSource ||
            destination.type() != AccountType.CUSTOMER
        ) {
            throw new InvalidTransferException(
                "SYSTEM_ACCOUNT_NOT_ALLOWED",
                "System accounts cannot be used directly in this operation"
            );
        }
        if (
            !source.currency().equals(currency) ||
            !destination.currency().equals(currency)
        ) {
            throw new InvalidTransferException(
                "CURRENCY_MISMATCH",
                "Both accounts must be denominated in " + currency
            );
        }
        if (
            source.type() == AccountType.CUSTOMER && source.balance() < amount
        ) {
            throw new InsufficientFundsException(
                source.id(),
                source.balance(),
                amount
            );
        }
    }
}
