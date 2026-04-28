package com.wallet.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wallet.application.command.deposit.DepositCommandHandler;
import com.wallet.application.command.settlement.SettlementCommandHandler;
import com.wallet.application.command.withdrawal.WithdrawalCommandHandler;
import com.wallet.application.port.out.LedgerRepository.LedgerEntryHistoryItem;
import com.wallet.application.port.out.LedgerRepository.LedgerEntryHistoryLine;
import com.wallet.application.result.BalancesResult;
import com.wallet.application.result.BalancesView;
import com.wallet.application.result.DepositResult;
import com.wallet.application.result.SettleResult;
import com.wallet.application.result.TransactionsResult;
import com.wallet.application.result.TransactionsView;
import com.wallet.application.result.WithdrawResult;
import com.wallet.application.service.WalletQueryService;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.infrastructure.config.OperationalSwitchPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = WalletController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("Wallet Controller (WebMvc)")
class WalletControllerWebMvcTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepositCommandHandler depositCommandHandler;

    @MockitoBean
    private WithdrawalCommandHandler withdrawalCommandHandler;

    @MockitoBean
    private WalletQueryService walletQueryService;

    @MockitoBean
    private SettlementCommandHandler settlementCommandHandler;

    @MockitoBean
    private MeterRegistry meterRegistry;

    @MockitoBean
    private OperationalSwitchPolicy operationalSwitchPolicy;

    @BeforeEach
    void stubMeterRegistry() {
        Counter counter = Mockito.mock(Counter.class);
        Mockito.when(meterRegistry.counter(any(String.class), any(String[].class))).thenReturn(counter);
        Mockito.when(operationalSwitchPolicy.isWithdrawalsEnabled()).thenReturn(true);
    }

    // ---- deposits -------------------------------------------------------

    @Test
    @DisplayName("POST /deposits returns ledger entry id on success")
    void depositReturnsLedgerEntryId() throws Exception {
        UUID ledgerEntryId = UUID.randomUUID();
        when(depositCommandHandler.deposit(
            eq(USER_ID),
            eq(SupportedCurrency.USD),
            any(BigDecimal.class),
            any(IdempotencyContext.class),
            any(AuditContext.class)
        )).thenAnswer(invocation -> new DepositResult.Success(ledgerEntryId));

        mockMvc.perform(
                post("/users/{userId}/deposits", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":50.00,\"currency\":\"USD\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.ledgerEntryId").value(ledgerEntryId.toString()));
    }

    @Test
    @DisplayName("POST /deposits returns 404 when user is unknown")
    void depositReturnsNotFoundForMissingUser() throws Exception {
        when(depositCommandHandler.deposit(any(), any(), any(), any(), any())).thenReturn(
            new DepositResult.UserNotFound("User not found")
        );

        mockMvc.perform(
                post("/users/{userId}/deposits", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":10,\"currency\":\"USD\"}")
            )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    // ---- withdrawals ----------------------------------------------------

    @Test
    @DisplayName("POST /withdrawals returns ledger entry id on success")
    void withdrawalReturnsLedgerEntryId() throws Exception {
        UUID ledgerEntryId = UUID.randomUUID();
        when(withdrawalCommandHandler.withdraw(any(), any(), any(), any(), any()))
            .thenReturn(new WithdrawResult.Success(ledgerEntryId));

        mockMvc.perform(
                post("/users/{userId}/withdrawals", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":20.00,\"currency\":\"USD\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.ledgerEntryId").value(ledgerEntryId.toString()));
    }

    @Test
    @DisplayName("POST /withdrawals returns 404 when user is unknown")
    void withdrawalReturnsNotFoundForMissingUser() throws Exception {
        when(withdrawalCommandHandler.withdraw(any(), any(), any(), any(), any()))
            .thenReturn(new WithdrawResult.UserNotFound("User not found"));

        mockMvc.perform(
                post("/users/{userId}/withdrawals", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":20.00,\"currency\":\"USD\"}")
            )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /withdrawals returns 503 when withdrawals are disabled via killswitch")
    void withdrawalReturnsServiceUnavailableWhenDisabled() throws Exception {
        Mockito.when(operationalSwitchPolicy.isWithdrawalsEnabled()).thenReturn(false);

        mockMvc.perform(
                post("/users/{userId}/withdrawals", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":20.00,\"currency\":\"USD\"}")
            )
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("SERVICE_DISABLED"));
    }

    @Test
    @DisplayName("POST /withdrawals returns 409 when funds are insufficient")
    void withdrawalReturnsConflictForInsufficientFunds() throws Exception {
        when(withdrawalCommandHandler.withdraw(any(), any(), any(), any(), any()))
            .thenReturn(new WithdrawResult.InsufficientFunds("Insufficient funds"));

        mockMvc.perform(
                post("/users/{userId}/withdrawals", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":9999.00,\"currency\":\"USD\"}")
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));
    }

    // ---- balances -------------------------------------------------------

    @Test
    @DisplayName("GET /balances maps successful query to currency map")
    void balancesReturnsAmounts() throws Exception {
        when(walletQueryService.getBalances(USER_ID)).thenReturn(
            new BalancesResult.Success(new BalancesView(Map.of("USD", new BigDecimal("12.50"))))
        );

        mockMvc.perform(get("/users/{userId}/balances", USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.USD").value(12.5));
    }

    @Test
    @DisplayName("GET /balances returns 404 when user is unknown")
    void balancesReturnsNotFound() throws Exception {
        when(walletQueryService.getBalances(USER_ID))
            .thenReturn(new BalancesResult.UserNotFound("User not found"));

        mockMvc.perform(get("/users/{userId}/balances", USER_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    // ---- pending-balances -----------------------------------------------

    @Test
    @DisplayName("GET /pending-balances returns pending amounts")
    void pendingBalancesReturnsAmounts() throws Exception {
        when(walletQueryService.getPendingBalances(USER_ID)).thenReturn(
            new BalancesResult.Success(new BalancesView(Map.of("USD", new BigDecimal("50.00"))))
        );

        mockMvc.perform(get("/users/{userId}/pending-balances", USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.USD").value(50.0));
    }

    // ---- transactions ---------------------------------------------------

    @Test
    @DisplayName("GET /transactions returns paged list of ledger entries")
    void transactionsReturnsPagedList() throws Exception {
        UUID entryId = UUID.randomUUID();
        LedgerEntryHistoryItem item = new LedgerEntryHistoryItem(
            entryId, "DEPOSIT", "2024-01-01T00:00:00Z",
            List.of(new LedgerEntryHistoryLine("USD", new BigDecimal("100.00")))
        );
        when(walletQueryService.getTransactions(eq(USER_ID), any(Integer.class), any()))
            .thenReturn(new TransactionsResult.Success(new TransactionsView(List.of(item))));

        mockMvc.perform(get("/users/{userId}/transactions", USER_ID).param("limit", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items[0].ledgerEntryId").value(entryId.toString()))
            .andExpect(jsonPath("$.items[0].entryType").value("DEPOSIT"))
            .andExpect(jsonPath("$.hasMore").value(false));
    }

    // ---- settle ---------------------------------------------------------

    @Test
    @DisplayName("POST /settle returns settled amounts on success")
    void settleReturnsSettledAmounts() throws Exception {
        when(settlementCommandHandler.settle(eq(USER_ID), any(AuditContext.class)))
            .thenReturn(new SettleResult.Success(Map.of("USD", new BigDecimal("90.00"))));

        mockMvc.perform(post("/users/{userId}/settle", USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.USD").value(90.0));
    }

    @Test
    @DisplayName("POST /settle returns empty map when nothing to settle")
    void settleReturnsEmptyWhenNothingPending() throws Exception {
        when(settlementCommandHandler.settle(eq(USER_ID), any(AuditContext.class)))
            .thenReturn(new SettleResult.NothingToSettle("No pending balance"));

        mockMvc.perform(post("/users/{userId}/settle", USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("POST /settle returns 404 when user is unknown")
    void settleReturnsNotFoundForMissingUser() throws Exception {
        when(settlementCommandHandler.settle(eq(USER_ID), any(AuditContext.class)))
            .thenReturn(new SettleResult.UserNotFound("User not found"));

        mockMvc.perform(post("/users/{userId}/settle", USER_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }
}
