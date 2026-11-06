package com.ledgerline.transfer;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.AbstractIntegrationTest;
import com.ledgerline.account.AccountService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class TransferApiIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccountService accounts;

    @Test
    void depositThenTransferThenFetch() throws Exception {
        UUID alice = accounts.create("Alice", "EUR").id();
        UUID bob = accounts.create("Bob", "EUR").id();

        mvc.perform(
            post("/api/v1/accounts/{id}/deposits", alice)
                .contentType(APPLICATION_JSON)
                .content(
                    "{\"amount\":2500,\"currency\":\"EUR\",\"reference\":\"salary\"}"
                )
        )
            .andExpect(status().isCreated())
            .andExpect(
                jsonPath("$.destinationAccountId").value(alice.toString())
            )
            .andExpect(jsonPath("$.amount").value(2500));

        String body = """
        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":1000,"currency":"EUR","reference":"dinner"}
        """.formatted(alice, bob);

        String response = mvc
            .perform(
                post("/api/v1/transfers")
                    .contentType(APPLICATION_JSON)
                    .content(body)
            )
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.reference").value("dinner"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String transferId = json.readTree(response).get("id").asText();

        mvc.perform(get("/api/v1/transfers/{id}", transferId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value(1000));

        mvc.perform(get("/api/v1/accounts/{id}", alice)).andExpect(
            jsonPath("$.balance").value(1500)
        );
        mvc.perform(get("/api/v1/accounts/{id}", bob)).andExpect(
            jsonPath("$.balance").value(1000)
        );
    }

    @Test
    void insufficientFundsReturns422() throws Exception {
        UUID alice = accounts.create("Alice", "EUR").id();
        UUID bob = accounts.create("Bob", "EUR").id();

        String body = """
        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":1,"currency":"EUR"}
        """.formatted(alice, bob);

        mvc.perform(
            post("/api/v1/transfers")
                .contentType(APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void nonPositiveAmountFailsValidation() throws Exception {
        String body = """
        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":0,"currency":"EUR"}
        """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mvc.perform(
            post("/api/v1/transfers")
                .contentType(APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownTransferReturns404() throws Exception {
        mvc.perform(get("/api/v1/transfers/{id}", UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }
}
