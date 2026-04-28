package com.wallet.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.adapter.out.fx.MockFxRateProvider;
import com.wallet.application.port.out.FxRateProvider;
import com.wallet.infrastructure.config.FxRuntimePolicy;
import com.wallet.infrastructure.exception.FxRateUnavailableException;
import com.wallet.integration.base.PostgresIntegrationTestBase;
import com.wallet.integration.support.WalletDatabaseCleaner;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("FX Resilience Integration Tests")
class FxResilienceIntegrationTest extends PostgresIntegrationTestBase {

    @DynamicPropertySource
    static void overrideQuoteTtl(DynamicPropertyRegistry registry) {
        registry.add("wallet.fx.quote-ttl-seconds", () -> "1");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    FxRateProvider fxRateProvider;

    @MockitoSpyBean
    MockFxRateProvider mockFxRateProvider;

    @MockitoSpyBean
    FxRuntimePolicy fxRuntimePolicy;

    @BeforeEach
    void cleanDatabase() {
        WalletDatabaseCleaner.truncateMutableTables(jdbcTemplate);
    }

    @AfterEach
    void resetSpies() {
        reset(fxRateProvider);
        reset(mockFxRateProvider);
        reset(fxRuntimePolicy);
    }

    @Test
    @DisplayName("FX rate provider unavailable during quote creation returns 503 FX_RATE_UNAVAILABLE")
    void fxProviderUnavailable_postQuote_returns503() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", "{\"amount\": \"100.00\", \"currency\": \"USD\"}");

        doThrow(new FxRateUnavailableException("provider-down-for-test", new RuntimeException("test-cause")))
            .when(fxRateProvider).rate(any(), any());

        String errorBody = mockMvc.perform(post("/users/" + userId + "/fx/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sellCurrency\":\"USD\",\"buyCurrency\":\"ARS\",\"sellAmount\":\"50.00\"}"))
            .andExpect(status().isServiceUnavailable())
            .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(errorBody).get("error").asText())
            .isEqualTo("FX_RATE_UNAVAILABLE");
    }

    @Test
    @DisplayName("Executing an expired FX quote returns 410 GONE (quote TTL=1s)")
    void expiredFxQuote_executeExchange_returns410Gone() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", "{\"amount\": \"100.00\", \"currency\": \"USD\"}");

        String quoteBody = mockMvc.perform(post("/users/" + userId + "/fx/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sellCurrency\":\"USD\",\"buyCurrency\":\"ARS\",\"sellAmount\":\"50.00\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String quoteId = objectMapper.readTree(quoteBody).get("quoteId").asText();

        Thread.sleep(1200);

        String errorBody = mockMvc.perform(post("/users/" + userId + "/fx/exchanges")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quoteId\":\"" + quoteId + "\"}"))
            .andExpect(status().isGone())
            .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(errorBody).get("error").asText())
            .isEqualTo("QUOTE_EXPIRED");
    }

    @Test
    @DisplayName("FX provider failure within stale TTL returns quote with servedFromStale=true")
    void fxProviderDegraded_withinStaleTtl_servesStaleRateWithFlag() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", "{\"amount\": \"100.00\", \"currency\": \"USD\"}");

        Mockito.doReturn(Duration.ofSeconds(30)).when(fxRuntimePolicy).staleRateTtl();

        // Warm the cache
        mockMvc.perform(post("/users/" + userId + "/fx/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sellCurrency\":\"USD\",\"buyCurrency\":\"ARS\",\"sellAmount\":\"10.00\"}"))
            .andExpect(status().isCreated());

        Mockito.doThrow(new RuntimeException("provider-down")).when(mockFxRateProvider).rate(any(), any());

        String quoteBody = mockMvc.perform(post("/users/" + userId + "/fx/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sellCurrency\":\"USD\",\"buyCurrency\":\"ARS\",\"sellAmount\":\"10.00\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(quoteBody).get("servedFromStale").asBoolean()).isTrue();
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
