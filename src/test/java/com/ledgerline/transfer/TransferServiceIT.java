package com.ledgerline.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerline.AbstractIntegrationTest;
import com.ledgerline.account.Account;
import com.ledgerline.account.AccountNotFoundException;
import com.ledgerline.account.AccountRepository;
import com.ledgerline.account.AccountService;
import com.ledgerline.ledger.Direction;
import com.ledgerline.ledger.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class TransferServiceIT extends AbstractIntegrationTest {

    @Autowired
    TransferService transfers;

    @Autowired
    AccountService accounts;

    @Autowired
    AccountRepository accountRepo;

    @Autowired
    JdbcClient jdbc;

    @Test
    void depositCreditsCustomerAndDebitsFundingAccount() {
        Account alice = accounts.create("Alice", "EUR");
        long fundingBefore = funding("EUR").balance();

        transfers.deposit(alice.id(), 5_000, "EUR", "top-up");

        assertThat(accounts.get(alice.id()).balance()).isEqualTo(5_000);
        assertThat(funding("EUR").balance()).isEqualTo(fundingBefore - 5_000);

        List<LedgerEntry> entries = accounts
            .entries(alice.id(), null, 10)
            .items();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).direction()).isEqualTo(Direction.CREDIT);
        assertThat(entries.get(0).balanceAfter()).isEqualTo(5_000);
    }

    @Test
    void transferMovesMoneyAndWritesBalancedEntries() {
        Account alice = accounts.create("Alice", "EUR");
        Account bob = accounts.create("Bob", "EUR");
        transfers.deposit(alice.id(), 1_000, "EUR", null);

        Transfer t = transfers.transfer(
            alice.id(),
            bob.id(),
            300,
            "EUR",
            "rent"
        );

        assertThat(accounts.get(alice.id()).balance()).isEqualTo(700);
        assertThat(accounts.get(bob.id()).balance()).isEqualTo(300);

        LedgerEntry debit = accounts
            .entries(alice.id(), null, 1)
            .items()
            .get(0);
        LedgerEntry credit = accounts.entries(bob.id(), null, 1).items().get(0);
        assertThat(debit.transferId()).isEqualTo(t.id());
        assertThat(debit.direction()).isEqualTo(Direction.DEBIT);
        assertThat(debit.balanceAfter()).isEqualTo(700);
        assertThat(credit.direction()).isEqualTo(Direction.CREDIT);
        assertThat(credit.balanceAfter()).isEqualTo(300);
    }

    @Test
    void insufficientFundsRollsBackEverything() {
        Account alice = accounts.create("Alice", "EUR");
        Account bob = accounts.create("Bob", "EUR");
        transfers.deposit(alice.id(), 100, "EUR", null);

        assertThatThrownBy(() ->
            transfers.transfer(alice.id(), bob.id(), 200, "EUR", null)
        ).isInstanceOf(InsufficientFundsException.class);

        assertThat(accounts.get(alice.id()).balance()).isEqualTo(100);
        assertThat(accounts.get(bob.id()).balance()).isEqualTo(0);
        Integer outgoing = jdbc
            .sql("SELECT count(*) FROM transfers WHERE source_account_id = :id")
            .param("id", alice.id())
            .query(Integer.class)
            .single();
        assertThat(outgoing).isZero();
    }

    @Test
    void currencyMismatchIsRejected() {
        Account alice = accounts.create("Alice", "EUR");
        Account carol = accounts.create("Carol", "USD");
        transfers.deposit(alice.id(), 100, "EUR", null);

        assertInvalid("CURRENCY_MISMATCH", () ->
            transfers.transfer(alice.id(), carol.id(), 10, "EUR", null)
        );
    }

    @Test
    void sameAccountIsRejected() {
        Account alice = accounts.create("Alice", "EUR");

        assertInvalid("SAME_ACCOUNT", () ->
            transfers.transfer(alice.id(), alice.id(), 10, "EUR", null)
        );
    }

    @Test
    void systemAccountsCannotBeUsedInCustomerTransfers() {
        Account alice = accounts.create("Alice", "EUR");
        UUID fundingId = funding("EUR").id();

        assertInvalid("SYSTEM_ACCOUNT_NOT_ALLOWED", () ->
            transfers.transfer(fundingId, alice.id(), 10, "EUR", null)
        );
        assertInvalid("SYSTEM_ACCOUNT_NOT_ALLOWED", () ->
            transfers.transfer(alice.id(), fundingId, 10, "EUR", null)
        );
        // Depositing into the funding account itself resolves to source == destination.
        assertInvalid("SAME_ACCOUNT", () ->
            transfers.deposit(fundingId, 10, "EUR", null)
        );
    }

    @Test
    void unknownAccountIsRejected() {
        Account alice = accounts.create("Alice", "EUR");

        assertThatThrownBy(() ->
            transfers.transfer(alice.id(), UUID.randomUUID(), 10, "EUR", null)
        ).isInstanceOf(AccountNotFoundException.class);
    }

    private Account funding(String currency) {
        return accountRepo.findSystemAccount(currency).orElseThrow();
    }

    private static void assertInvalid(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
            InvalidTransferException.class,
            e -> assertThat(e.code()).isEqualTo(code)
        );
    }
}
