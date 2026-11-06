package com.ledgerline.reconciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerline.AbstractIntegrationTest;
import com.ledgerline.account.AccountService;
import com.ledgerline.transfer.TransferService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class ReconciliationIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountService accounts;

    @Autowired
    TransferService transfers;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void resetDatabase() {
        // TRUNCATE does not fire the append-only row triggers; it is a test-only reset.
        jdbc.sql(
            "TRUNCATE ledger_entries, transfers, idempotency_keys, accounts RESTART IDENTITY CASCADE"
        ).update();
        jdbc.sql(
            """
            INSERT INTO accounts (name, currency, account_type, balance) VALUES
                ('Funding EUR', 'EUR', 'SYSTEM', 0),
                ('Funding USD', 'USD', 'SYSTEM', 0),
                ('Funding GBP', 'GBP', 'SYSTEM', 0)
            """
        ).update();
    }

    @Test
    void healthyLedgerIsReportedConsistent() throws Exception {
        UUID alice = accounts.create("Alice", "EUR").id();
        UUID bob = accounts.create("Bob", "EUR").id();
        transfers.deposit(alice, 5_000, "EUR", null);
        transfers.transfer(alice, bob, 1_200, "EUR", null);

        mvc.perform(get("/api/v1/reconciliation"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.consistent").value(true))
            .andExpect(jsonPath("$.counts.accounts").value(5))
            .andExpect(jsonPath("$.counts.transfers").value(2))
            .andExpect(jsonPath("$.counts.ledgerEntries").value(4))
            .andExpect(jsonPath("$.balanceTotalsByCurrency.EUR").value(0))
            .andExpect(jsonPath("$.negativeCustomerBalances").value(0))
            .andExpect(jsonPath("$.balanceLedgerMismatches").value(0))
            .andExpect(jsonPath("$.malformedTransfers").value(0));
    }

    @Test
    void tamperedBalanceIsDetected() throws Exception {
        UUID alice = accounts.create("Alice", "EUR").id();
        transfers.deposit(alice, 1_000, "EUR", null);

        // Simulate corruption that bypasses the ledger: money appears from nowhere.
        jdbc.sql("UPDATE accounts SET balance = balance + 7 WHERE id = :id")
            .param("id", alice)
            .update();

        mvc.perform(get("/api/v1/reconciliation"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.consistent").value(false))
            .andExpect(jsonPath("$.balanceLedgerMismatches").value(1))
            .andExpect(jsonPath("$.balanceTotalsByCurrency.EUR").value(7));
    }
}
