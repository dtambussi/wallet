package com.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.port.out.LedgerRepository.PostLedgerEntryResult;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.ledger.LedgerLine;
import com.wallet.infrastructure.config.FxRuntimePolicy;
import com.wallet.infrastructure.web.RequestIdMdcFilter;
import com.wallet.integration.base.PostgresIntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import com.wallet.adapter.out.payments.MockWithdrawalProvider;
import com.wallet.adapter.out.payments.PayoutWorker;
import com.wallet.integration.support.WalletDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;

@SuppressWarnings("DataFlowIssue")
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Wallet Integration Flows Test")
class WalletIntegrationTest extends PostgresIntegrationTestBase {

    @DynamicPropertySource
    static void walletSpecificDockerProperties(DynamicPropertyRegistry registry) {
        // With max-attempts=1 the first failure immediately triggers a reversal — keeps tests fast.
        registry.add("wallet.payout.max-attempts", () -> "1");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    LedgerRepository ledgerRepository;

    @Autowired
    PayoutWorker payoutWorker;

    @Autowired
    FxRuntimePolicy fxRuntimePolicy;

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoSpyBean
    MockWithdrawalProvider mockWithdrawalProvider;

    @BeforeEach
    void cleanDatabase() {
        WalletDatabaseCleaner.truncateMutableTables(jdbcTemplate);
    }

    @AfterEach
    void cleanup() {
        // Reset spy to default behaviour (always succeed) then drain any leftover outbox records.
        reset(mockWithdrawalProvider);
        payoutWorker.drainOutbox();
    }

    @Test
    @DisplayName("Should complete deposit, transfer, FX exchange, and balance checks")
    void depositTransferFxAndBalances() throws Exception {
        String alice = createUser();
        String bob = createUser();

        postJson("/users/" + alice + "/deposits", """
            {"amount": "1000.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        postJson("/users/" + bob + "/deposits", """
            {"amount": "500.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        postJson("/users/" + alice + "/transfers", """
            {"toUserId": "%s", "amount": "100.00", "currency": "USD"}
            """.formatted(bob)).andExpect(status().isCreated());

        String quoteBody = postJson("/users/" + alice + "/fx/quotes", """
            {"sellCurrency": "USD", "buyCurrency": "ARS", "sellAmount": "50.00"}
            """).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String quoteId = objectMapper.readTree(quoteBody).get("quoteId").asText();

        postJson("/users/" + alice + "/fx/exchanges", """
            {"quoteId": "%s"}
            """.formatted(quoteId)).andExpect(status().isCreated());

        String aliceBalancesBody = mockMvc.perform(get("/users/" + alice + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode aliceBalances = objectMapper.readTree(aliceBalancesBody);
        assertThat(new BigDecimal(aliceBalances.get("USD").asText())).isEqualByComparingTo("850.00");
        assertThat(new BigDecimal(aliceBalances.get("ARS").asText())).isEqualByComparingTo("69500.00");

        String transactionsBody = mockMvc.perform(get("/users/" + alice + "/transactions").param("limit", "10"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode transactionsPage = objectMapper.readTree(transactionsBody);
        JsonNode transactionItems = transactionsPage.get("items");

        assertThat(transactionItems).hasSize(3);
        assertThat(transactionsPage.get("nextCursor").isNull()).isTrue();
        assertThat(transactionsPage.get("hasMore").asBoolean()).isFalse();
        assertThat(transactionsPage.at("/items/0/ledgerEntryId").asText()).isNotBlank();
        assertThat(transactionsPage.at("/items/1/ledgerEntryId").asText()).isNotBlank();
        assertThat(transactionsPage.at("/items/2/ledgerEntryId").asText()).isNotBlank();
        assertThat(transactionItems.toString()).contains("DEPOSIT", "TRANSFER", "FX_EXCHANGE");
    }

    @Test
    @DisplayName("Should apply idempotent deposit only once")
    void idempotentDepositSingleEntry() throws Exception {
        String userId = createUser();
        String depositBody = "{\"amount\": \"10.00\", \"currency\": \"USD\"}";
        mockMvc.perform(post("/users/" + userId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "pay-1")
                .content(depositBody))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/users/" + userId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "pay-1")
                .content(depositBody))
            .andExpect(status().isCreated());

        String balancesBody = mockMvc.perform(get("/users/" + userId + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(balancesBody).get("USD").asText()))
            .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("Should return transactions page with hasMore and nextCursor")
    void transactionsPaginationReturnsHasMoreAndNextCursor() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", """
            {"amount": "100.00", "currency": "USD"}
            """).andExpect(status().isCreated());
        postJson("/users/" + userId + "/deposits", """
            {"amount": "200.00", "currency": "USD"}
            """).andExpect(status().isCreated());
        postJson("/users/" + userId + "/deposits", """
            {"amount": "300.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        String firstPageBody = mockMvc.perform(get("/users/" + userId + "/transactions").param("limit", "2"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode firstPage = objectMapper.readTree(firstPageBody);
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("hasMore").asBoolean()).isTrue();
        assertThat(firstPage.get("nextCursor").asText()).isNotBlank();

        String secondPageBody = mockMvc.perform(
                get("/users/" + userId + "/transactions")
                    .param("limit", "2")
                    .param("cursor", firstPage.get("nextCursor").asText())
            )
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode secondPage = objectMapper.readTree(secondPageBody);
        assertThat(secondPage.get("items")).hasSize(1);
        assertThat(secondPage.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Should reject withdrawal when funds are insufficient")
    void insufficientFundsRejected() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", """
            {"amount": "10.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        String errorResponseBody = mockMvc.perform(post("/users/" + userId + "/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": \"999.00\", \"currency\": \"USD\"}"))
            .andExpect(status().isConflict())
            .andReturn().getResponse().getContentAsString();
        JsonNode errorResponse = objectMapper.readTree(errorResponseBody);
        assertThat(errorResponse.get("error").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(errorResponse.get("message").asText()).contains("Insufficient funds");
    }

    @Test
    @DisplayName("Should allow only one concurrent withdrawal to succeed")
    void concurrentWithdrawalsOnlyOneCanSucceed() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", """
            {"amount": "100.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> task = () -> {
            startLatch.await(5, TimeUnit.SECONDS);
            return mockMvc.perform(post("/users/" + userId + "/withdrawals")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\": \"80.00\", \"currency\": \"USD\"}"))
                .andReturn().getResponse().getStatus();
        };

        Future<Integer> first = executor.submit(task);
        Future<Integer> second = executor.submit(task);
        startLatch.countDown();

        int statusA = first.get(10, TimeUnit.SECONDS);
        int statusB = second.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder(201, 409);

        String balancesBody = mockMvc.perform(get("/users/" + userId + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(balancesBody).get("USD").asText()))
            .isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("Should consume the same FX quote only once under concurrency")
    void concurrentFxExchangeSameQuoteSingleConsume() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", """
            {"amount": "200.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        String quoteBody = postJson("/users/" + userId + "/fx/quotes", """
            {"sellCurrency": "USD", "buyCurrency": "ARS", "sellAmount": "100.00"}
            """).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String quoteId = objectMapper.readTree(quoteBody).get("quoteId").asText();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> exchangeTask = () -> {
            startLatch.await(5, TimeUnit.SECONDS);
            return mockMvc.perform(post("/users/" + userId + "/fx/exchanges")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quoteId\": \"" + quoteId + "\"}"))
                .andReturn().getResponse().getStatus();
        };

        Future<Integer> first = executor.submit(exchangeTask);
        Future<Integer> second = executor.submit(exchangeTask);
        startLatch.countDown();

        int statusA = first.get(10, TimeUnit.SECONDS);
        int statusB = second.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(List.of(statusA, statusB)).contains(201);
        assertThat(List.of(statusA, statusB)).containsAnyOf(409, 410);

        String balancesBody = mockMvc.perform(get("/users/" + userId + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode balances = objectMapper.readTree(balancesBody);
        assertThat(new BigDecimal(balances.get("USD").asText())).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(balances.get("ARS").asText())).isEqualByComparingTo("139000.00");
    }

    @Test
    @DisplayName("Should replay concurrent idempotent deposits with a single ledger effect")
    void concurrentIdempotentDepositCreatesSingleLedgerEffect() throws Exception {
        String userId = createUser();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> depositTask = () -> {
            startLatch.await(5, TimeUnit.SECONDS);
            return mockMvc.perform(post("/users/" + userId + "/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "concurrent-pay-1")
                    .content("{\"amount\": \"25.00\", \"currency\": \"USD\"}"))
                .andReturn().getResponse().getStatus();
        };

        Future<Integer> first = executor.submit(depositTask);
        Future<Integer> second = executor.submit(depositTask);
        startLatch.countDown();

        int statusA = first.get(10, TimeUnit.SECONDS);
        int statusB = second.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder(201, 201);

        String balancesBody = mockMvc.perform(get("/users/" + userId + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(balancesBody).get("USD").asText()))
            .isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("Should return conflict when idempotency key is reused with a different body")
    void idempotencyKeyWithDifferentBodyReturnsConflict() throws Exception {
        String userId = createUser();
        String idempotencyKey = "same-key-twice";
        mockMvc.perform(post("/users/" + userId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content("{\"amount\": \"10.00\", \"currency\": \"USD\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/users/" + userId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content("{\"amount\": \"99.00\", \"currency\": \"USD\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Should debit sender and create pending balances for batch recipients")
    void batchTransferDebitsSenderAndCreatesPendingRecipientBalances() throws Exception {
        String sender = createUser();
        String firstRecipient = createUser();
        String secondRecipient = createUser();

        postJson("/users/" + sender + "/deposits", """
            {"amount": "500.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        mockMvc.perform(post("/users/" + sender + "/batch-transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"transfers":[
                      {"toUserId":"%s","amount":"120.00","currency":"USD"},
                      {"toUserId":"%s","amount":"80.00","currency":"USD"}
                    ]}
                    """.formatted(firstRecipient, secondRecipient)))
            .andExpect(status().isCreated());

        String senderBalances = mockMvc.perform(get("/users/" + sender + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(senderBalances).get("USD").asText()))
            .isEqualByComparingTo("300.00");

        String firstPending = mockMvc.perform(get("/users/" + firstRecipient + "/pending-balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(firstPending).get("USD").asText()))
            .isEqualByComparingTo("120.00");

        String secondPending = mockMvc.perform(get("/users/" + secondRecipient + "/pending-balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(secondPending).get("USD").asText()))
            .isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Should allow only one concurrent batch transfer from same sender")
    void concurrentBatchTransfersFromSameSenderOnlyOneCanSucceed() throws Exception {
        String sender = createUser();
        String recipientA = createUser();
        String recipientB = createUser();

        postJson("/users/" + sender + "/deposits", """
            {"amount": "100.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> firstBatchTask = () -> {
            startLatch.await(5, TimeUnit.SECONDS);
            return mockMvc.perform(post("/users/" + sender + "/batch-transfers")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"transfers":[
                          {"toUserId":"%s","amount":"80.00","currency":"USD"}
                        ]}
                        """.formatted(recipientA)))
                .andReturn().getResponse().getStatus();
        };
        Callable<Integer> secondBatchTask = () -> {
            startLatch.await(5, TimeUnit.SECONDS);
            return mockMvc.perform(post("/users/" + sender + "/batch-transfers")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"transfers":[
                          {"toUserId":"%s","amount":"80.00","currency":"USD"}
                        ]}
                        """.formatted(recipientB)))
                .andReturn().getResponse().getStatus();
        };

        Future<Integer> first = executor.submit(firstBatchTask);
        Future<Integer> second = executor.submit(secondBatchTask);
        startLatch.countDown();

        int statusA = first.get(10, TimeUnit.SECONDS);
        int statusB = second.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder(201, 409);

        String senderBalances = mockMvc.perform(get("/users/" + sender + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(senderBalances).get("USD").asText()))
            .isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("Should apply batch transfer idempotency effect only once")
    void batchTransferIdempotencyAppliesEffectOnce() throws Exception {
        String sender = createUser();
        String recipient = createUser();

        postJson("/users/" + sender + "/deposits", """
            {"amount": "300.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        String requestBody = """
            {"transfers":[
              {"toUserId":"%s","amount":"75.00","currency":"USD"}
            ]}
            """.formatted(recipient);
        String idempotencyKey = "batch-payroll-1";

        mockMvc.perform(post("/users/" + sender + "/batch-transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/users/" + sender + "/batch-transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated());

        String senderBalances = mockMvc.perform(get("/users/" + sender + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(senderBalances).get("USD").asText()))
            .isEqualByComparingTo("225.00");

        String recipientPending = mockMvc.perform(get("/users/" + recipient + "/pending-balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(recipientPending).get("USD").asText()))
            .isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("Should move pending balance to available on settlement")
    void settleMovesPendingToAvailableAndClearsPending() throws Exception {
        String sender = createUser();
        String recipient = createUser();

        postJson("/users/" + sender + "/deposits", """
            {"amount": "200.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        mockMvc.perform(post("/users/" + sender + "/batch-transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"transfers":[
                      {"toUserId":"%s","amount":"90.00","currency":"USD"}
                    ]}
                    """.formatted(recipient)))
            .andExpect(status().isCreated());

        String beforePending = mockMvc.perform(get("/users/" + recipient + "/pending-balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(beforePending).get("USD").asText()))
            .isEqualByComparingTo("90.00");

        mockMvc.perform(post("/users/" + recipient + "/settle"))
            .andExpect(status().isOk());

        String afterPending = mockMvc.perform(get("/users/" + recipient + "/pending-balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(afterPending).isEmpty()).isTrue();

        String availableBalances = mockMvc.perform(get("/users/" + recipient + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(availableBalances).get("USD").asText()))
            .isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("Should dispatch payout and patch provider reference in ledger metadata")
    void withdrawalWorkerDispatchesPayoutAndPatchesProviderRef() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", """
            {"amount": "50.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        // Withdrawal is accepted immediately (debit committed, payout queued).
        postJson("/users/" + userId + "/withdrawals", """
            {"amount":"20.00","currency":"USD"}
            """).andExpect(status().isCreated());

        // Balance reflects the debit before the worker runs.
        String balanceAfterWithdrawal = mockMvc.perform(get("/users/" + userId + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(balanceAfterWithdrawal).get("USD").asText()))
            .isEqualByComparingTo("30.00");

        // Worker dispatches to provider and patches ledger metadata.
        payoutWorker.drainOutbox();

        // providerRef in ledger metadata is no longer "pending".
        String metadata = jdbcTemplate.queryForObject(
            "SELECT metadata::text FROM ledger_entries WHERE entry_type = 'WITHDRAWAL' AND idempotency_key LIKE ?",
            String.class,
            userId + "|%"
        );
        assertThat(metadata).contains("mock-payout-");
        assertThat(metadata).doesNotContain("\"providerRef\":\"pending\"");
    }

    @Test
    @DisplayName("Should reverse withdrawal after payout retries are exhausted")
    void withdrawalProviderFailureReversesBalanceAfterRetryExhaustion() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", """
            {"amount": "50.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        doThrow(new RuntimeException("provider-down"))
            .when(mockWithdrawalProvider)
            .initiatePayout(any(UUID.class), any(), any());

        // Withdrawal is accepted (debit committed, payout queued) — no longer a 500.
        postJson("/users/" + userId + "/withdrawals", """
            {"amount":"7.00","currency":"USD"}
            """).andExpect(status().isCreated());

        // Balance is 43 immediately after the debit.
        String balanceAfterDebit = mockMvc.perform(get("/users/" + userId + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(balanceAfterDebit).get("USD").asText()))
            .isEqualByComparingTo("43.00");

        // Worker retries exhaust (max-attempts=1 in tests) → reversal credit posted.
        payoutWorker.drainOutbox();

        // Balance restored to original 50.
        String balanceAfterReversal = mockMvc.perform(get("/users/" + userId + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(balanceAfterReversal).get("USD").asText()))
            .isEqualByComparingTo("50.00");

        // A WITHDRAWAL_REVERSAL ledger entry was created.
        int reversalCount = jdbcTemplate.queryForObject(
            "SELECT count(*)::int FROM ledger_entries WHERE entry_type = 'WITHDRAWAL_REVERSAL'",
            Integer.class
        );
        assertThat(reversalCount).isGreaterThanOrEqualTo(1);

        // Audit trail records the reversal.
        int reversalAuditRows = jdbcTemplate.queryForObject(
            "SELECT count(*)::int FROM financial_audit_events WHERE command_type = 'PAYOUT_WORKER' AND outcome = 'PAYOUT_REVERSED'",
            Integer.class
        );
        assertThat(reversalAuditRows).isGreaterThanOrEqualTo(1);
        assertThat(counter("wallet_provider_health_total", "provider", "payments", "outcome", "degraded"))
            .isGreaterThanOrEqualTo(1.0d);
    }

    @Test
    @DisplayName("Should link X-Request-Id across ledger and audit rows")
    void xRequestIdLinksLedgerRowAndAuditLog() throws Exception {
        String userId = createUser();
        String requestId = "audit-trace-1";
        postJson(
            "/users/" + userId + "/deposits",
            """
            {"amount": "1.00", "currency": "USD"}
            """,
            requestId
        ).andExpect(status().isCreated());
        int audit = jdbcTemplate.queryForObject(
            "SELECT count(*)::int FROM financial_audit_events WHERE correlation_id = ?",
            Integer.class,
            requestId
        );
        int ledger = jdbcTemplate.queryForObject(
            "SELECT count(*)::int FROM ledger_entries WHERE correlation_id = ?",
            Integer.class,
            requestId
        );
        assertThat(audit).isEqualTo(1);
        assertThat(ledger).isEqualTo(1);
    }

    @Test
    @DisplayName("Should write audit event for insufficient funds")
    void insufficientFundsWritesAuditEvent() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", """
            {"amount": "10.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        mockMvc.perform(post("/users/" + userId + "/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": \"999.00\", \"currency\": \"USD\"}"))
            .andExpect(status().isConflict());

        int insufficientFundsAuditRows = jdbcTemplate.queryForObject(
            """
            SELECT count(*)::int
            FROM financial_audit_events
            WHERE command_type = 'WITHDRAWAL'
              AND outcome = 'INSUFFICIENT_FUNDS'
              AND subject_user_id = ?
            """,
            Integer.class,
            UUID.fromString(userId)
        );
        assertThat(insufficientFundsAuditRows).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Should debit sender currency and credit recipient target currency in cross-currency transfer")
    void crossCurrencyTransferDebitsFromCurrencyAndCreditsToRecipientInToCurrency() throws Exception {
        String sender = createUser();
        String recipient = createUser();

        postJson("/users/" + sender + "/deposits", """
            {"amount": "100.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        // Transfer 10 USD → ARS (mock ~1390 ARS/USD → recipient gets 13900 ARS)
        mockMvc.perform(post("/users/" + sender + "/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"toUserId":"%s","amount":"10.00","currency":"USD","toCurrency":"ARS"}
                    """.formatted(recipient)))
            .andExpect(status().isCreated());

        String senderBalances = mockMvc.perform(get("/users/" + sender + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(senderBalances).get("USD").asText()))
            .isEqualByComparingTo("90.00");

        String recipientBalances = mockMvc.perform(get("/users/" + recipient + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(recipientBalances).get("ARS").asText()))
            .isEqualByComparingTo("13900.00");

        // Applied rate and amounts must be recorded in ledger metadata for auditability.
        String metadata = jdbcTemplate.queryForObject(
            "SELECT metadata::text FROM ledger_entries WHERE entry_type = 'CROSS_CURRENCY_TRANSFER' AND idempotency_key LIKE ?",
            String.class,
            sender + "|%"
        );
        assertThat(metadata).contains("appliedRate");
        assertThat(metadata).contains("sentAmount");
        assertThat(metadata).contains("receivedAmount");
        assertThat(metadata).contains("fxPricedAt");
        assertThat(metadata).contains("fxSource");
        assertThat(metadata).contains("fxServedFromStale");
        assertThat(counter("wallet_money_flow_total", "operation", "transfer", "outcome", "success"))
            .isGreaterThanOrEqualTo(1.0d);
    }

    @Test
    @DisplayName("Should replay cross-currency transfer idempotency with single effect")
    void crossCurrencyTransferIdempotencyReplaysSingleEffect() throws Exception {
        String sender = createUser();
        String recipient = createUser();
        postJson("/users/" + sender + "/deposits", """
            {"amount": "100.00", "currency": "USD"}
            """).andExpect(status().isCreated());

        String requestBody = """
            {"toUserId":"%s","amount":"10.00","currency":"USD","toCurrency":"ARS"}
            """.formatted(recipient);
        String idempotencyKey = "xfer-usd-ars-1";

        String firstResponse = mockMvc.perform(post("/users/" + sender + "/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String secondResponse = mockMvc.perform(post("/users/" + sender + "/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String firstLedgerEntryId = objectMapper.readTree(firstResponse).get("ledgerEntryId").asText();
        String secondLedgerEntryId = objectMapper.readTree(secondResponse).get("ledgerEntryId").asText();
        assertThat(secondLedgerEntryId).isEqualTo(firstLedgerEntryId);

        String senderBalances = mockMvc.perform(get("/users/" + sender + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(senderBalances).get("USD").asText()))
            .isEqualByComparingTo("90.00");

        String recipientBalances = mockMvc.perform(get("/users/" + recipient + "/balances"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(objectMapper.readTree(recipientBalances).get("ARS").asText()))
            .isEqualByComparingTo("13900.00");
    }

    @Test
    @DisplayName("Should refresh FX stale-rate TTL from runtime config table")
    void fxStaleRateTtlCanBeUpdatedAtRuntimeFromDatabase() throws Exception {
        jdbcTemplate.update(
            "UPDATE runtime_config SET value = ? WHERE key = 'wallet.fx.stale-rate-ttl-seconds'",
            "0"
        );
        Thread.sleep(5200);
        assertThat(fxRuntimePolicy.staleRateTtl()).isEqualTo(Duration.ZERO);

        jdbcTemplate.update(
            "UPDATE runtime_config SET value = ? WHERE key = 'wallet.fx.stale-rate-ttl-seconds'",
            "30"
        );
        Thread.sleep(5200);
        assertThat(fxRuntimePolicy.staleRateTtl()).isEqualTo(Duration.ofSeconds(30));
    }

    private double counter(String name, String... tags) {
        return meterRegistry.get(name).tags(tags).counter().count();
    }

    @Test
    @DisplayName("Should return created then replayed result for same ledger idempotency key")
    void postLedgerEntryReturnsCreatedThenReplayed() throws Exception {
        String userId = createUser();
        UUID user = UUID.fromString(userId);
        String scopedKey = userId + "|typed-result-test-1";
        String fingerprint = "typed-result-test-fingerprint";

        PostLedgerEntryResult first = ledgerRepository.postLedgerEntry(
            scopedKey,
            "TEST_DEPOSIT",
            List.of(LedgerLine.available(user, "USD", new BigDecimal("10.00"))),
            Map.of("source", "test"),
            fingerprint,
            "typed-result-correlation-1"
        );
        PostLedgerEntryResult second = ledgerRepository.postLedgerEntry(
            scopedKey,
            "TEST_DEPOSIT",
            List.of(LedgerLine.available(user, "USD", new BigDecimal("10.00"))),
            Map.of("source", "test"),
            fingerprint,
            "typed-result-correlation-2"
        );

        assertThat(first).isInstanceOf(PostLedgerEntryResult.Created.class);
        assertThat(second).isInstanceOf(PostLedgerEntryResult.Replayed.class);
        assertThat(second.ledgerEntryId()).isEqualTo(first.ledgerEntryId());
    }

    private String createUser() throws Exception {
        String createUserResponseBody = mockMvc.perform(post("/users"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(createUserResponseBody).get("userId").asText();
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return postJson(path, json, null);
    }

    private ResultActions postJson(String path, String json, String xRequestId) throws Exception {
        var builder = post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json);
        if (xRequestId != null) {
            builder = builder.header(RequestIdMdcFilter.HEADER, xRequestId);
        }
        return mockMvc.perform(builder);
    }
}
