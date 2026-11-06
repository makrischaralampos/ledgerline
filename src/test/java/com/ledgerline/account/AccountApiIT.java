package com.ledgerline.account;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

class AccountApiIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void createsAndFetchesAnAccount() throws Exception {
        String body = json.writeValueAsString(
            new AccountController.CreateAccountRequest("Alice", "EUR")
        );

        String location = mvc
            .perform(
                post("/api/v1/accounts")
                    .contentType(APPLICATION_JSON)
                    .content(body)
            )
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.name").value("Alice"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.type").value("CUSTOMER"))
            .andExpect(jsonPath("$.balance").value(0))
            .andReturn()
            .getResponse()
            .getHeader("Location");

        mvc.perform(get(location))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Alice"));
    }

    @Test
    void unknownAccountReturnsProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidRequestReturnsFieldErrors() throws Exception {
        mvc.perform(
            post("/api/v1/accounts")
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"\",\"currency\":\"eur\"}")
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void unsupportedCurrencyIsRejected() throws Exception {
        mvc.perform(
            post("/api/v1/accounts")
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"Bob\",\"currency\":\"XYZ\"}")
        )
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void entriesArePaginatedNewestFirst() throws Exception {
        UUID a = insertAccount();
        UUID b = insertAccount();
        for (int i = 0; i < 3; i++) {
            seedTransfer(a, b, 10);
        }

        String nextCursor = mvc
            .perform(get("/api/v1/accounts/{id}/entries?limit=2", a))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String cursor = json.readTree(nextCursor).get("nextCursor").asText();

        mvc.perform(
            get("/api/v1/accounts/{id}/entries?limit=2&cursor={c}", a, cursor)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    // ---- helpers ----

    private UUID insertAccount() {
        UUID id = UUID.randomUUID();
        jdbc.sql(
            "INSERT INTO accounts (id, name, currency, account_type, balance) VALUES (:id, 't', 'EUR', 'CUSTOMER', 1000)"
        )
            .param("id", id)
            .update();
        return id;
    }

    private void seedTransfer(UUID from, UUID to, long amount) {
        tx.executeWithoutResult(s -> {
            UUID id = UUID.randomUUID();
            jdbc.sql(
                "INSERT INTO transfers (id, source_account_id, destination_account_id, amount, currency) VALUES (:id, :f, :t, :a, 'EUR')"
            )
                .param("id", id)
                .param("f", from)
                .param("t", to)
                .param("a", amount)
                .update();
            jdbc.sql(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, currency, balance_after) VALUES (:id, :acc, 'DEBIT', :a, 'EUR', 0)"
            )
                .param("id", id)
                .param("acc", from)
                .param("a", amount)
                .update();
            jdbc.sql(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, currency, balance_after) VALUES (:id, :acc, 'CREDIT', :a, 'EUR', 0)"
            )
                .param("id", id)
                .param("acc", to)
                .param("a", amount)
                .update();
        });
    }
}
