package com.wallet.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.adapter.out.payments.MockWithdrawalProvider;
import com.wallet.adapter.out.payments.PayoutWorker;
import com.wallet.domain.audit.AuditCommandTypes;
import com.wallet.domain.audit.AuditOutcomes;
import com.wallet.integration.base.PostgresIntegrationTestBase;
import com.wallet.integration.support.WalletDatabaseCleaner;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.mockito.Mockito;
import java.util.UUID;

/**
 * Exercises every {@code command_type} / {@code outcome} pair that the product records for <strong>success and
 * operational</strong> paths (plus idempotency replay and nothing-to-settle). Rejection-only outcomes remain covered in
 * WalletIntegrationTest (e.g. insufficient funds, correlation samples).
 */
@SuppressWarnings("DataFlowIssue")
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Financial audit events (DB)")
class FinancialAuditEventsIntegrationTest extends PostgresIntegrationTestBase {

    @DynamicPropertySource
    static void payoutTuningForTests(DynamicPropertyRegistry registry) {
        registry.add("wallet.payout.max-attempts", () -> "1");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PayoutWorker payoutWorker;

    @MockitoSpyBean
    private MockWithdrawalProvider mockWithdrawalProvider;

    @BeforeEach
    void cleanDatabase() {
        WalletDatabaseCleaner.truncateMutableTables(jdbcTemplate);
    }

    @AfterEach
    void resetPayoutStubs() {
        Mockito.reset(mockWithdrawalProvider);
        payoutWorker.drainOutbox();
    }

    @Test
    @DisplayName("Deposit, transfer, FX quote, and FX exchange each append SUCCESS rows for the path user")
    void depositThenTransferThenFxAppendsChainedAudits() throws Exception {
        String alice = createUser();
        String bob = createUser();

        postJson("/users/" + bob + "/deposits", """
            {"amount": "500.00", "currency": "USD"}
            """).andExpect(status().isCreated());
        postJson("/users/" + alice + "/deposits", """
            {"amount": "1000.00", "currency": "USD"}
            """).andExpect(status().isCreated());
        postJson(
            "/users/" + alice + "/transfers",
            """
            {"toUserId": "%s", "amount": "100.00", "currency": "USD"}
            """.formatted(bob)
        ).andExpect(status().isCreated());

        String quoteBody = postJson(
            "/users/" + alice + "/fx/quotes",
            """
            {"sellCurrency": "USD", "buyCurrency": "ARS", "sellAmount": "50.00"}
            """
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String quoteId = objectMapper.readTree(quoteBody).get("quoteId").asText();
        postJson(
            "/users/" + alice + "/fx/exchanges",
            """
            {"quoteId": "%s"}
            """.formatted(quoteId)
        ).andExpect(status().isCreated());

        assertThat(
            auditEventCount(UUID.fromString(alice), AuditCommandTypes.DEPOSIT, AuditOutcomes.SUCCESS)
        ).isEqualTo(1);
        assertThat(
            auditEventCount(UUID.fromString(alice), AuditCommandTypes.TRANSFER, AuditOutcomes.SUCCESS)
        ).isEqualTo(1);
        assertThat(
            auditEventCount(UUID.fromString(alice), AuditCommandTypes.FX_QUOTE, AuditOutcomes.SUCCESS)
        ).isEqualTo(1);
        assertThat(
            auditEventCount(UUID.fromString(alice), AuditCommandTypes.FX_EXCHANGE, AuditOutcomes.SUCCESS)
        ).isEqualTo(1);
        assertThat(
            auditEventCount(UUID.fromString(bob), AuditCommandTypes.DEPOSIT, AuditOutcomes.SUCCESS)
        ).isEqualTo(1);

        int fxQuoteWithoutLedger = jdbcTemplate.queryForObject(
            """
            SELECT count(*)::int FROM financial_audit_events
            WHERE subject_user_id = ?::uuid
              AND command_type = ?
              AND outcome = ?
              AND ledger_entry_id IS NULL
            """,
            Integer.class,
            alice,
            AuditCommandTypes.FX_QUOTE,
            AuditOutcomes.SUCCESS
        );
        assertThat(fxQuoteWithoutLedger).isEqualTo(1);

        int fxWithQuoteInDetails = jdbcTemplate.queryForObject(
            """
            SELECT count(*)::int FROM financial_audit_events
            WHERE subject_user_id = ?::uuid
              AND command_type = ?
              AND (details->'quoteId') IS NOT NULL
            """,
            Integer.class,
            alice,
            AuditCommandTypes.FX_QUOTE
        );
        assertThat(fxWithQuoteInDetails).isEqualTo(1);
    }

    @Test
    @DisplayName("Same idempotency key and body: second deposit appends IDEMPOTENCY_REPLAY")
    void depositIdempotencyReplayIsAudited() throws Exception {
        String userId = createUser();
        String key = "idem-replay-audit-1";
        String body = "{\"amount\": \"12.00\", \"currency\": \"USD\"}";

        mockMvc
            .perform(
                post("/users/" + userId + "/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", key)
                    .content(body)
            )
            .andExpect(status().isCreated());
        mockMvc
            .perform(
                post("/users/" + userId + "/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", key)
                    .content(body)
            )
            .andExpect(status().isCreated());

        assertThat(
            auditEventCount(UUID.fromString(userId), AuditCommandTypes.DEPOSIT, AuditOutcomes.SUCCESS)
        ).isEqualTo(1);
        assertThat(
            auditEventCount(
                UUID.fromString(userId), AuditCommandTypes.DEPOSIT, AuditOutcomes.IDEMPOTENCY_REPLAY
            )
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("Batch transfer and settlement append BATCH_TRANSFER and SETTLEMENT SUCCESS rows")
    void batchAndSettlementAppendsBatchAndSettleSuccess() throws Exception {
        String sender = createUser();
        String recipient = createUser();
        postJson(
            "/users/" + sender + "/deposits",
            """
            {"amount": "300.00", "currency": "USD"}
            """
        ).andExpect(status().isCreated());
        mockMvc
            .perform(
                post("/users/" + sender + "/batch-transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"transfers":[
                          {"toUserId":"%s","amount":"50.00","currency":"USD"}
                        ]}
                        """
                            .formatted(recipient)
                    )
            )
            .andExpect(status().isCreated());
        assertThat(
            auditEventCount(
                UUID.fromString(sender), AuditCommandTypes.BATCH_TRANSFER, AuditOutcomes.SUCCESS
            )
        ).isEqualTo(1);
        assertThat(
            auditEventCount(
                UUID.fromString(recipient), AuditCommandTypes.BATCH_TRANSFER, AuditOutcomes.SUCCESS
            )
        ).isEqualTo(0);

        mockMvc
            .perform(post("/users/" + recipient + "/settle").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
        assertThat(
            auditEventCount(
                UUID.fromString(recipient), AuditCommandTypes.SETTLEMENT, AuditOutcomes.SUCCESS
            )
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("Settlement with no pending balance appends NOTHING_TO_SETTLE")
    void settlementWithNothingPendingAppendsNothingToSettle() throws Exception {
        String userId = createUser();
        mockMvc
            .perform(post("/users/" + userId + "/settle").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
        assertThat(
            auditEventCount(
                UUID.fromString(userId), AuditCommandTypes.SETTLEMENT, AuditOutcomes.NOTHING_TO_SETTLE
            )
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("Cross-currency transfer appends one TRANSFER SUCCESS for sender")
    void crossCurrencyTransferAppendsTransferSuccess() throws Exception {
        String sender = createUser();
        String recipient = createUser();
        postJson(
            "/users/" + sender + "/deposits",
            """
            {"amount": "200.00", "currency": "USD"}
            """
        ).andExpect(status().isCreated());
        mockMvc
            .perform(
                post("/users/" + sender + "/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"toUserId":"%s","amount":"5.00","currency":"USD","toCurrency":"ARS"}
                        """
                            .formatted(recipient)
                    )
            )
            .andExpect(status().isCreated());
        assertThat(
            auditEventCount(
                UUID.fromString(sender), AuditCommandTypes.TRANSFER, AuditOutcomes.SUCCESS
            )
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("Accepted withdrawal and successful payout worker append WITHDRAWAL SUCCESS and PAYOUT_DISPATCHED")
    void withdrawalAndPayoutWorkerAppendsWithdrawalAndPayoutRows() throws Exception {
        String userId = createUser();
        postJson(
            "/users/" + userId + "/deposits",
            """
            {"amount": "40.00", "currency": "USD"}
            """
        ).andExpect(status().isCreated());
        postJson(
            "/users/" + userId + "/withdrawals",
            """
            {"amount": "5.00", "currency": "USD"}
            """
        ).andExpect(status().isCreated());
        assertThat(
            auditEventCount(
                UUID.fromString(userId), AuditCommandTypes.WITHDRAWAL, AuditOutcomes.SUCCESS
            )
        ).isEqualTo(1);
        assertThat(
            auditEventCount(
                UUID.fromString(userId), AuditCommandTypes.PAYOUT_WORKER, AuditOutcomes.PAYOUT_DISPATCHED
            )
        ).isEqualTo(0);
        payoutWorker.drainOutbox();
        assertThat(
            auditEventCount(
                UUID.fromString(userId), AuditCommandTypes.PAYOUT_WORKER, AuditOutcomes.PAYOUT_DISPATCHED
            )
        ).isEqualTo(1);
    }

    private int auditEventCount(
        UUID subjectUserId,
        String commandType,
        String outcome
    ) {
        return jdbcTemplate.queryForObject(
            """
            SELECT count(*)::int FROM financial_audit_events
            WHERE subject_user_id = ? AND command_type = ? AND outcome = ?
            """,
            Integer.class,
            subjectUserId,
            commandType,
            outcome
        );
    }

    private String createUser() throws Exception {
        return objectMapper
            .readTree(
                mockMvc
                    .perform(post("/users"))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString()
            )
            .get("userId")
            .asText();
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(
            post(path).contentType(MediaType.APPLICATION_JSON).content(json)
        );
    }
}
