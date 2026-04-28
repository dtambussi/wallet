package com.wallet.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.integration.base.PostgresIntegrationTestBase;
import com.wallet.integration.support.WalletDatabaseCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Metrics Integration Tests")
class WalletMetricsIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void cleanDatabase() {
        WalletDatabaseCleaner.truncateMutableTables(jdbcTemplate);
    }

    @Test
    @DisplayName("wallet_money_flow_total emitted for deposit, withdrawal, transfer, fx_exchange")
    void moneyOperations_emitMoneyFlowMetric() throws Exception {
        String alice = createUser();
        String bob = createUser();

        postJson("/users/" + alice + "/deposits", "{\"amount\": \"100.00\", \"currency\": \"USD\"}");
        postJson("/users/" + bob + "/deposits", "{\"amount\": \"50.00\", \"currency\": \"USD\"}");

        mockMvc.perform(post("/users/" + alice + "/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"amount\": \"10.00\", \"currency\": \"USD\"}"))
            .andExpect(status().is2xxSuccessful());

        postJson("/users/" + alice + "/transfers",
            "{\"toUserId\":\"" + bob + "\",\"amount\":\"5.00\",\"currency\":\"USD\"}");

        String quoteBody = postJson("/users/" + alice + "/fx/quotes",
            "{\"sellCurrency\":\"USD\",\"buyCurrency\":\"ARS\",\"sellAmount\":\"10.00\"}");
        String quoteId = objectMapper.readTree(quoteBody).get("quoteId").asText();

        postJson("/users/" + alice + "/fx/exchanges", "{\"quoteId\":\"" + quoteId + "\"}");

        assertThat(counter("wallet_money_flow_total", "operation", "deposit", "outcome", "success")).isGreaterThanOrEqualTo(1.0);
        assertThat(counter("wallet_money_flow_total", "operation", "withdrawal", "outcome", "success")).isGreaterThanOrEqualTo(1.0);
        assertThat(counter("wallet_money_flow_total", "operation", "transfer", "outcome", "success")).isGreaterThanOrEqualTo(1.0);
        assertThat(counter("wallet_money_flow_total", "operation", "fx_exchange", "outcome", "success")).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("wallet_provider_health_total emitted for fx provider")
    void fxQuoteCreation_emitsProviderHealthMetric() throws Exception {
        String userId = createUser();
        postJson("/users/" + userId + "/deposits", "{\"amount\": \"100.00\", \"currency\": \"USD\"}");

        postJson("/users/" + userId + "/fx/quotes",
            "{\"sellCurrency\":\"USD\",\"buyCurrency\":\"ARS\",\"sellAmount\":\"20.00\"}");

        assertThat(counter("wallet_provider_health_total", "provider", "fx", "outcome", "request")).isGreaterThanOrEqualTo(1.0);
    }

private double counter(String name, String... tags) {
        return meterRegistry.get(name).tags(tags).counter().count();
    }

    private String createUser() throws Exception {
        String body = mockMvc.perform(post("/users"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("userId").asText();
    }

    private String postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().is2xxSuccessful())
            .andReturn().getResponse().getContentAsString();
    }
}
