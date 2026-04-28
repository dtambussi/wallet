package com.wallet.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.adapter.out.payments.MockWithdrawalProvider;
import com.wallet.adapter.out.payments.PayoutWorker;
import com.wallet.integration.base.PostgresIntegrationTestBase;
import com.wallet.integration.support.WalletDatabaseCleaner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Wallet Guarantee Stress Integration Tests")
class WalletGuaranteeStressIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PayoutWorker payoutWorker;

    @MockitoSpyBean
    MockWithdrawalProvider mockWithdrawalProvider;

    @BeforeEach
    void cleanDatabase() {
        WalletDatabaseCleaner.truncateMutableTables(jdbcTemplate);
    }

    @AfterEach
    void cleanup() {
        reset(mockWithdrawalProvider);
        payoutWorker.drainOutbox();
    }

    // ---- G2: concurrent balance enforcement stress ----------------------

    @Test
    @DisplayName("8 concurrent withdrawals of 20 USD from 100 USD account — exactly 5 succeed, balance never goes negative (G2 stress)")
    void eightConcurrentWithdrawals_exactly5Succeed_balanceNeverNegative() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", "{\"amount\": \"100.00\", \"currency\": \"USD\"}");

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/users/" + userId + "/withdrawals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"20.00\", \"currency\": \"USD\"}"))
                    .andReturn().getResponse().getStatus();
            });
        }

        List<Future<Integer>> futures = tasks.stream().map(executor::submit).toList();
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get(15, TimeUnit.SECONDS));
        }
        executor.shutdownNow();

        long successCount = statuses.stream().filter(s -> s == 201).count();
        long rejectCount = statuses.stream().filter(s -> s == 409).count();
        assertThat(successCount).isEqualTo(5);
        assertThat(rejectCount).isEqualTo(3);

        String balancesBody = mockMvc.perform(get("/users/" + userId + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(balancesBody).get("USD").asText()))
            .isEqualByComparingTo("0.00");
    }

    // ---- G5: idempotency stampede stress --------------------------------

    @Test
    @DisplayName("8 concurrent deposits with same idempotency key — all return 201, single ledger effect, balance = 25 USD (G5 stampede)")
    void eightConcurrentDeposits_sameIdempotencyKey_all201_singleLedgerEffect() throws Exception {
        String userId = createUser();

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/users/" + userId + "/deposits")
                        .header("Idempotency-Key", "stampede-deposit-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"25.00\", \"currency\": \"USD\"}"))
                    .andReturn().getResponse().getStatus();
            });
        }

        List<Future<Integer>> futures = tasks.stream().map(executor::submit).toList();
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get(15, TimeUnit.SECONDS));
        }
        executor.shutdownNow();

        assertThat(statuses).allMatch(s -> s == 201);

        String balancesBody = mockMvc.perform(get("/users/" + userId + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(balancesBody).get("USD").asText()))
            .isEqualByComparingTo("25.00");
    }

    // ---- G6: SKIP LOCKED prevents double dispatch ----------------------

    @Test
    @DisplayName("3 concurrent outbox drains — each payout row dispatched exactly once, no double dispatch (G6 SKIP LOCKED)")
    void threeConcurrentOutboxDrains_eachPayoutRowProcessedExactlyOnce() throws Exception {
        int outboxCount = 3;
        for (int i = 0; i < outboxCount; i++) {
            String userId = createUser();
            postJson("/users/" + userId + "/deposits", "{\"amount\": \"50.00\", \"currency\": \"USD\"}");
            mockMvc.perform(post("/users/" + userId + "/withdrawals")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\": \"10.00\", \"currency\": \"USD\"}"))
                .andExpect(status().isCreated());
        }

        ExecutorService executor = Executors.newFixedThreadPool(outboxCount);
        CountDownLatch readyLatch = new CountDownLatch(outboxCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<Void>> drainTasks = new ArrayList<>();
        for (int i = 0; i < outboxCount; i++) {
            drainTasks.add(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                payoutWorker.drainOutbox();
                return null;
            });
        }

        List<Future<Void>> futures = drainTasks.stream().map(executor::submit).toList();
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<Void> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        // Each of the 3 outbox rows must be claimed and dispatched exactly once
        verify(mockWithdrawalProvider, times(outboxCount)).initiatePayout(any(), any(), any());
    }

    // ---- lock ordering: bidirectional transfers -------------------------

    @Test
    @DisplayName("Bidirectional A→B and B→A simultaneous transfers complete without deadlock")
    void bidirectionalTransfers_concurrentOpposingDirections_noDeadlock() throws Exception {
        String alice = createUser();
        String bob = createUser();

        postJson("/users/" + alice + "/deposits", "{\"amount\": \"100.00\", \"currency\": \"USD\"}");
        postJson("/users/" + bob + "/deposits", "{\"amount\": \"100.00\", \"currency\": \"USD\"}");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> aliceToBob = () -> {
            startLatch.await(5, TimeUnit.SECONDS);
            return mockMvc.perform(post("/users/" + alice + "/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"toUserId\":\"%s\",\"amount\":\"10.00\",\"currency\":\"USD\"}".formatted(bob)))
                .andReturn().getResponse().getStatus();
        };
        Callable<Integer> bobToAlice = () -> {
            startLatch.await(5, TimeUnit.SECONDS);
            return mockMvc.perform(post("/users/" + bob + "/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"toUserId\":\"%s\",\"amount\":\"10.00\",\"currency\":\"USD\"}".formatted(alice)))
                .andReturn().getResponse().getStatus();
        };

        Future<Integer> f1 = executor.submit(aliceToBob);
        Future<Integer> f2 = executor.submit(bobToAlice);
        startLatch.countDown();

        int statusA = f1.get(10, TimeUnit.SECONDS);
        int statusB = f2.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(List.of(statusA, statusB)).contains(201);
        assertThat(statusA).isIn(201, 409);
        assertThat(statusB).isIn(201, 409);
    }

    // ---- idempotency: no-key deduplication by body fingerprint ----------

    @Test
    @DisplayName("Same deposit body POSTed twice without Idempotency-Key header — single ledger effect")
    void blindRetryDeduplication_noHeader_sameBodyTwice_singleLedgerEffect() throws Exception {
        String userId = createUser();
        String body = "{\"amount\": \"15.00\", \"currency\": \"USD\"}";

        mockMvc.perform(post("/users/" + userId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/users/" + userId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        String balancesBody = mockMvc.perform(get("/users/" + userId + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(balancesBody).get("USD").asText()))
            .isEqualByComparingTo("15.00");
    }

    private String createUser() throws Exception {
        String body = mockMvc.perform(post("/users"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("userId").asText();
    }

    private void postJson(String path, String json) throws Exception {
        mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isCreated());
    }
}
