package com.ledgerline.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.AbstractIntegrationTest;
import com.ledgerline.account.AccountService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccountService accounts;

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    JdbcClient jdbc;

    @Test
    void retryWithSameKeyReplaysTheOriginalResponseAndMovesMoneyOnce()
        throws Exception {
        UUID alice = funded("EUR", 1_000);
        UUID bob = accounts.create("Bob", "EUR").id();
        String key = UUID.randomUUID().toString();
        String body = transferBody(alice, bob, 100);

        MvcResult first = mvc
            .perform(
                post("/api/v1/transfers")
                    .header("Idempotency-Key", key)
                    .contentType(APPLICATION_JSON)
                    .content(body)
            )
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andReturn();

        MvcResult second = mvc
            .perform(
                post("/api/v1/transfers")
                    .header("Idempotency-Key", key)
                    .contentType(APPLICATION_JSON)
                    .content(body)
            )
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(
            first.getResponse().getContentAsString()
        );
        assertThat(accounts.get(alice).balance()).isEqualTo(900);
        assertThat(accounts.get(bob).balance()).isEqualTo(100);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() throws Exception {
        UUID alice = funded("EUR", 1_000);
        UUID bob = accounts.create("Bob", "EUR").id();
        String key = UUID.randomUUID().toString();

        mvc.perform(
            post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(APPLICATION_JSON)
                .content(transferBody(alice, bob, 100))
        ).andExpect(status().isCreated());

        mvc.perform(
            post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(APPLICATION_JSON)
                .content(transferBody(alice, bob, 999))
        )
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(accounts.get(bob).balance()).isEqualTo(100);
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        mvc.perform(
            post("/api/v1/transfers")
                .contentType(APPLICATION_JSON)
                .content(transferBody(UUID.randomUUID(), UUID.randomUUID(), 1))
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void malformedKeyIsRejected() throws Exception {
        mvc.perform(
            post("/api/v1/transfers")
                .header("Idempotency-Key", "has spaces in it")
                .contentType(APPLICATION_JSON)
                .content(transferBody(UUID.randomUUID(), UUID.randomUUID(), 1))
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void failedRequestDoesNotBurnTheKey() throws Exception {
        UUID alice = accounts.create("Alice", "EUR").id();
        UUID bob = accounts.create("Bob", "EUR").id();
        String key = UUID.randomUUID().toString();
        String body = transferBody(alice, bob, 500);

        mvc.perform(
            post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        deposit(alice, 1_000);

        mvc.perform(
            post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"));

        assertThat(accounts.get(bob).balance()).isEqualTo(500);
    }

    @Test
    void concurrentRequestsWithSameKeyExecuteExactlyOnce() throws Exception {
        UUID alice = funded("EUR", 10_000);
        UUID bob = accounts.create("Bob", "EUR").id();
        String key = UUID.randomUUID().toString();
        String body = transferBody(alice, bob, 100);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    MvcResult result = mvc
                        .perform(
                            post("/api/v1/transfers")
                                .header("Idempotency-Key", key)
                                .contentType(APPLICATION_JSON)
                                .content(body)
                        )
                        .andExpect(status().isCreated())
                        .andReturn();
                    return json
                        .readTree(result.getResponse().getContentAsString())
                        .get("id")
                        .asText();
                })
            );
        }
        ready.await();
        go.countDown();

        Set<String> transferIds = new HashSet<>();
        for (Future<String> f : futures) {
            transferIds.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(transferIds).hasSize(1);
        assertThat(accounts.get(alice).balance()).isEqualTo(9_900);
        assertThat(accounts.get(bob).balance()).isEqualTo(100);
    }

    @Test
    void expiredKeysArePurgedAndFreshOnesKept() {
        jdbc.sql(
            """
            INSERT INTO idempotency_keys
                (idempotency_key, request_hash, status, response_status, response_body, created_at, completed_at)
            VALUES ('old-key', 'h', 'COMPLETED', 201, CAST('{}' AS jsonb),
                    now() - interval '2 days', now() - interval '2 days'),
                   ('fresh-key', 'h', 'COMPLETED', 201, CAST('{}' AS jsonb), now(), now())
            """
        ).update();

        idempotency.purgeExpired(Duration.ofHours(24));

        assertThat(keyExists("old-key")).isFalse();
        assertThat(keyExists("fresh-key")).isTrue();
    }

    // ---- helpers ----

    private UUID funded(String currency, long amount) throws Exception {
        UUID id = accounts.create("Funded", currency).id();
        deposit(id, amount);
        return id;
    }

    private void deposit(UUID accountId, long amount) throws Exception {
        mvc.perform(
            post("/api/v1/accounts/{id}/deposits", accountId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"currency\":\"EUR\"}")
        ).andExpect(status().isCreated());
    }

    private static String transferBody(UUID from, UUID to, long amount) {
        return """
        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":%d,"currency":"EUR"}
        """.formatted(from, to, amount);
    }

    private boolean keyExists(String key) {
        return (
            jdbc
                .sql(
                    "SELECT count(*) FROM idempotency_keys WHERE idempotency_key = :k"
                )
                .param("k", key)
                .query(Integer.class)
                .single() > 0
        );
    }
}
