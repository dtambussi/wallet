package com.wallet.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wallet.application.command.fx.FxCommandHandler;
import com.wallet.application.command.fx.FxCommandHandler.FxQuoteResponse;
import com.wallet.application.result.FxExchangeResult;
import com.wallet.application.result.FxQuoteResult;
import com.wallet.domain.audit.AuditContext;
import com.wallet.infrastructure.config.OperationalSwitchPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
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

@WebMvcTest(controllers = FxController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("FX Controller (WebMvc)")
class FxControllerWebMvcTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FxCommandHandler fxCommandHandler;

    @MockitoBean
    private MeterRegistry meterRegistry;

    @MockitoBean
    private OperationalSwitchPolicy operationalSwitchPolicy;

    @BeforeEach
    void stubMeterRegistry() {
        Counter counter = Mockito.mock(Counter.class);
        Mockito.when(meterRegistry.counter(any(String.class), any(String[].class))).thenReturn(counter);
        Mockito.when(operationalSwitchPolicy.isFxEnabled()).thenReturn(true);
    }

    // ---- quotes ---------------------------------------------------------

    @Test
    @DisplayName("POST /fx/quotes returns 201 with quote details on success")
    void createQuoteReturnsQuoteDetails() throws Exception {
        UUID quoteId = UUID.randomUUID();
        FxQuoteResponse quoteResponse = new FxQuoteResponse(
            quoteId.toString(), "USD", "ARS",
            new BigDecimal("50.00"), new BigDecimal("69500.00"),
            "2024-01-01T00:00:30Z", "2024-01-01T00:00:00Z", "MockFx", false
        );
        when(fxCommandHandler.createQuote(eq(USER_ID), any(), any(), any(), any(AuditContext.class)))
            .thenReturn(new FxQuoteResult.Success(quoteResponse));

        mockMvc.perform(
                post("/users/{userId}/fx/quotes", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sellCurrency\":\"USD\",\"buyCurrency\":\"ARS\",\"sellAmount\":\"50.00\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.quoteId").value(quoteId.toString()))
            .andExpect(jsonPath("$.sellCurrency").value("USD"))
            .andExpect(jsonPath("$.buyCurrency").value("ARS"))
            .andExpect(jsonPath("$.servedFromStale").value(false));
    }

    @Test
    @DisplayName("POST /fx/quotes returns 404 when user is unknown")
    void createQuoteReturnsNotFoundForMissingUser() throws Exception {
        when(fxCommandHandler.createQuote(any(), any(), any(), any(), any()))
            .thenReturn(new FxQuoteResult.UserNotFound("User not found"));

        mockMvc.perform(
                post("/users/{userId}/fx/quotes", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sellCurrency\":\"USD\",\"buyCurrency\":\"ARS\",\"sellAmount\":\"50.00\"}")
            )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /fx/quotes returns 400 when sell currency is missing")
    void createQuoteReturnsBadRequestForMissingCurrency() throws Exception {
        mockMvc.perform(
                post("/users/{userId}/fx/quotes", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"buyCurrency\":\"ARS\",\"sellAmount\":\"50.00\"}")
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /fx/quotes returns 503 when FX is disabled via killswitch")
    void createQuoteReturnsServiceUnavailableWhenDisabled() throws Exception {
        Mockito.when(operationalSwitchPolicy.isFxEnabled()).thenReturn(false);

        mockMvc.perform(
                post("/users/{userId}/fx/quotes", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sellCurrency\":\"USD\",\"buyCurrency\":\"ARS\",\"sellAmount\":\"50.00\"}")
            )
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("SERVICE_DISABLED"));
    }

    // ---- exchanges ------------------------------------------------------

    @Test
    @DisplayName("POST /fx/exchanges returns 503 when FX is disabled via killswitch")
    void executeExchangeReturnsServiceUnavailableWhenDisabled() throws Exception {
        Mockito.when(operationalSwitchPolicy.isFxEnabled()).thenReturn(false);
        UUID quoteId = UUID.randomUUID();

        mockMvc.perform(
                post("/users/{userId}/fx/exchanges", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quoteId\":\"" + quoteId + "\"}")
            )
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("SERVICE_DISABLED"));
    }

    @Test
    @DisplayName("POST /fx/exchanges returns 201 with ledger entry id on success")
    void executeExchangeReturnsLedgerEntryId() throws Exception {
        UUID ledgerEntryId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        when(fxCommandHandler.executeExchange(eq(USER_ID), eq(quoteId), any(), any(AuditContext.class)))
            .thenReturn(new FxExchangeResult.Success(ledgerEntryId));

        mockMvc.perform(
                post("/users/{userId}/fx/exchanges", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quoteId\":\"" + quoteId + "\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.ledgerEntryId").value(ledgerEntryId.toString()));
    }

    @Test
    @DisplayName("POST /fx/exchanges returns 410 when quote is expired")
    void executeExchangeReturnsGoneForExpiredQuote() throws Exception {
        UUID quoteId = UUID.randomUUID();
        when(fxCommandHandler.executeExchange(any(), any(), any(), any()))
            .thenReturn(new FxExchangeResult.QuoteExpired("Quote expired"));

        mockMvc.perform(
                post("/users/{userId}/fx/exchanges", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quoteId\":\"" + quoteId + "\"}")
            )
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.error").value("QUOTE_EXPIRED"));
    }

    @Test
    @DisplayName("POST /fx/exchanges returns 404 when quote does not exist")
    void executeExchangeReturnsNotFoundForMissingQuote() throws Exception {
        UUID quoteId = UUID.randomUUID();
        when(fxCommandHandler.executeExchange(any(), any(), any(), any()))
            .thenReturn(new FxExchangeResult.QuoteNotFound("Quote not found"));

        mockMvc.perform(
                post("/users/{userId}/fx/exchanges", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quoteId\":\"" + quoteId + "\"}")
            )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("QUOTE_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /fx/exchanges returns 409 when quote is already used")
    void executeExchangeReturnsConflictForUsedQuote() throws Exception {
        UUID quoteId = UUID.randomUUID();
        when(fxCommandHandler.executeExchange(any(), any(), any(), any()))
            .thenReturn(new FxExchangeResult.QuoteUsed("Quote already consumed"));

        mockMvc.perform(
                post("/users/{userId}/fx/exchanges", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quoteId\":\"" + quoteId + "\"}")
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("QUOTE_USED"));
    }

    @Test
    @DisplayName("POST /fx/exchanges returns 409 when funds are insufficient")
    void executeExchangeReturnsConflictForInsufficientFunds() throws Exception {
        UUID quoteId = UUID.randomUUID();
        when(fxCommandHandler.executeExchange(any(), any(), any(), any()))
            .thenReturn(new FxExchangeResult.InsufficientFunds("Insufficient funds"));

        mockMvc.perform(
                post("/users/{userId}/fx/exchanges", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quoteId\":\"" + quoteId + "\"}")
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));
    }
}
