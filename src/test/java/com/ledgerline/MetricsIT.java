package com.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerline.account.AccountService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class MetricsIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry registry;

    @Autowired
    AccountService accounts;

    @Test
    void completedAndReplayedRequestsAreCountedSeparately() throws Exception {
        UUID alice = accounts.create("Alice", "EUR").id();
        String key = UUID.randomUUID().toString();
        double completedBefore = count(
            "ledgerline.transfers.completed",
            "type",
            "deposit"
        );
        double replaysBefore = count(
            "ledgerline.idempotency.replays",
            "operation",
            "deposit"
        );

        for (int i = 0; i < 2; i++) {
            mvc.perform(
                post("/api/v1/accounts/{id}/deposits", alice)
                    .header("Idempotency-Key", key)
                    .contentType(APPLICATION_JSON)
                    .content("{\"amount\":100,\"currency\":\"EUR\"}")
            ).andExpect(status().isCreated());
        }

        assertThat(
            count("ledgerline.transfers.completed", "type", "deposit") -
                completedBefore
        ).isEqualTo(1);
        assertThat(
            count("ledgerline.idempotency.replays", "operation", "deposit") -
                replaysBefore
        ).isEqualTo(1);
    }

    @Test
    void domainErrorsAreCountedByCode() throws Exception {
        double before = count("ledgerline.errors", "code", "ACCOUNT_NOT_FOUND");

        mvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID())).andExpect(
            status().isNotFound()
        );

        assertThat(
            count("ledgerline.errors", "code", "ACCOUNT_NOT_FOUND") - before
        ).isEqualTo(1);
    }

    private double count(String name, String tagKey, String tagValue) {
        return registry.counter(name, tagKey, tagValue).count();
    }
}
