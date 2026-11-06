package com.ledgerline.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerline.AbstractIntegrationTest;
import com.ledgerline.account.AccountRepository;
import com.ledgerline.account.AccountService;
import com.ledgerline.support.LedgerAssertions;
import com.ledgerline.support.Parallel;
import com.ledgerline.support.Parallel.Outcome;
import com.ledgerline.transfer.InsufficientFundsException;
import com.ledgerline.transfer.TransferService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class TransferConcurrencyIT extends AbstractIntegrationTest {

    /** Kept below the Hikari pool size (20) so threads never starve each other of connections. */
    private static final int THREADS = 16;

    @Autowired
    TransferService transfers;

    @Autowired
    AccountService accounts;

    @Autowired
    AccountRepository accountRepo;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mvc;

    private LedgerAssertions ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerAssertions(jdbc);
    }

    @Test
    void opposingTransfersBetweenTwoAccountsNeverDeadlock() throws Exception {
        UUID a = funded("A", 1_000);
        UUID b = funded("B", 1_000);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 1_600; i++) {
            boolean forward = i % 2 == 0;
            UUID from = forward ? a : b;
            UUID to = forward ? b : a;
            tasks.add(() -> {
                transfers.transfer(from, to, 1, "EUR", null);
                return null;
            });
        }

        List<Outcome<Void>> outcomes = Parallel.run(THREADS, tasks);

        assertNoErrors(outcomes);
        // 800 transfers each way of 1 unit: net effect is zero.
        assertThat(accounts.get(a).balance()).isEqualTo(1_000);
        assertThat(accounts.get(b).balance()).isEqualTo(1_000);
        ledger.assertConsistent(List.of(a, b), 2_000);
    }

    @Test
    void randomMeshOfTransfersConservesMoney() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(funded("Mesh-" + i, 5_000));
        }

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            tasks.add(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int from = random.nextInt(ids.size());
                int to =
                    (from + 1 + random.nextInt(ids.size() - 1)) % ids.size();
                long amount = 1 + random.nextLong(3_000);
                try {
                    transfers.transfer(
                        ids.get(from),
                        ids.get(to),
                        amount,
                        "EUR",
                        null
                    );
                    return true;
                } catch (InsufficientFundsException expected) {
                    return false;
                }
            });
        }

        List<Outcome<Boolean>> outcomes = Parallel.run(THREADS, tasks);

        assertNoErrors(outcomes); // anything other than INSUFFICIENT_FUNDS is a failure
        long succeeded = outcomes
            .stream()
            .filter(o -> Boolean.TRUE.equals(o.value()))
            .count();
        assertThat(succeeded).isPositive();

        Long persisted = jdbc
            .sql(
                "SELECT count(*) FROM transfers WHERE source_account_id IN (:ids)"
            )
            .param("ids", ids)
            .query(Long.class)
            .single();
        assertThat(persisted)
            .as("one transfer row per successful request")
            .isEqualTo(succeeded);

        ledger.assertConsistent(ids, 50_000);
    }

    @Test
    void hotAccountCanNeverBeOverdrawn() throws Exception {
        UUID hot = funded("Hot", 1_000);
        List<UUID> destinations = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            destinations.add(accounts.create("Dest-" + i, "EUR").id());
        }

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID destination = destinations.get(i % destinations.size());
            tasks.add(() -> {
                try {
                    transfers.transfer(hot, destination, 100, "EUR", null);
                    return true;
                } catch (InsufficientFundsException expected) {
                    return false;
                }
            });
        }

        List<Outcome<Boolean>> outcomes = Parallel.run(THREADS, tasks);

        assertNoErrors(outcomes);
        long succeeded = outcomes
            .stream()
            .filter(o -> Boolean.TRUE.equals(o.value()))
            .count();
        assertThat(succeeded)
            .as("1000 / 100 = exactly 10 transfers can succeed")
            .isEqualTo(10);
        assertThat(accounts.get(hot).balance()).isZero();

        long received = destinations
            .stream()
            .mapToLong(id -> accounts.get(id).balance())
            .sum();
        assertThat(received).isEqualTo(1_000);

        List<UUID> all = new ArrayList<>(destinations);
        all.add(hot);
        ledger.assertConsistent(all, 1_000);
    }

    @Test
    void concurrentDepositsAreAllApplied() throws Exception {
        UUID account = accounts.create("Depositor", "EUR").id();
        long fundingBefore = accountRepo
            .findSystemAccount("EUR")
            .orElseThrow()
            .balance();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> {
                transfers.deposit(account, 10, "EUR", null);
                return null;
            });
        }

        assertNoErrors(Parallel.run(THREADS, tasks));

        assertThat(accounts.get(account).balance()).isEqualTo(1_000);
        assertThat(accountRepo.findSystemAccount("EUR").orElseThrow().balance())
            .as("funding account is debited by exactly the deposited total")
            .isEqualTo(fundingBefore - 1_000);
        assertThat(accounts.entries(account, null, 100).items()).hasSize(100);
        ledger.assertConsistent(List.of(account), 1_000);
    }

    @Test
    void duplicateRequestStormAcrossManyKeysMovesMoneyExactlyOncePerKey()
        throws Exception {
        UUID alice = funded("Alice", 1_000);
        UUID bob = accounts.create("Bob", "EUR").id();
        String body = """
        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":10,"currency":"EUR"}
        """.formatted(alice, bob);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int k = 0; k < 25; k++) {
            String key = UUID.randomUUID().toString();
            for (int duplicate = 0; duplicate < 4; duplicate++) {
                tasks.add(() -> {
                    var response = mvc
                        .perform(
                            post("/api/v1/transfers")
                                .header("Idempotency-Key", key)
                                .contentType(APPLICATION_JSON)
                                .content(body)
                        )
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
                    return response.getHeader("Idempotent-Replayed") != null;
                });
            }
        }
        Collections.shuffle(tasks);

        List<Outcome<Boolean>> outcomes = Parallel.run(THREADS, tasks);

        assertNoErrors(outcomes);
        long replays = outcomes
            .stream()
            .filter(o -> Boolean.TRUE.equals(o.value()))
            .count();
        assertThat(replays)
            .as("100 requests = 25 originals + 75 replays")
            .isEqualTo(75);

        assertThat(accounts.get(alice).balance()).isEqualTo(750);
        assertThat(accounts.get(bob).balance()).isEqualTo(250);
        Long persisted = jdbc
            .sql("SELECT count(*) FROM transfers WHERE source_account_id = :id")
            .param("id", alice)
            .query(Long.class)
            .single();
        assertThat(persisted).isEqualTo(25);
        ledger.assertConsistent(List.of(alice, bob), 1_000);
    }

    // ---- helpers ----

    private UUID funded(String name, long amount) {
        UUID id = accounts.create(name, "EUR").id();
        transfers.deposit(id, amount, "EUR", null);
        return id;
    }

    private static <T> void assertNoErrors(List<Outcome<T>> outcomes) {
        assertThat(outcomes).allSatisfy(o ->
            assertThat(o.error()).as("task failed").isNull()
        );
    }
}
