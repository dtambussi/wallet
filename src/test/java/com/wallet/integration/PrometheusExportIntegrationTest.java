package com.wallet.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.integration.base.PostgresIntegrationTestBase;
import com.wallet.integration.support.WalletDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.prometheus.metrics.export.enabled=true")
@DisplayName("Prometheus scrape export (integration)")
class PrometheusExportIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        WalletDatabaseCleaner.truncateMutableTables(jdbcTemplate);
    }

    @Test
    @DisplayName("/actuator/prometheus exports wallet_money_flow_total and wallet_provider_health_total with correct labels")
    void prometheusEndpoint_exportsWalletMetricsWithLabels() throws Exception {
        String alice = createUser();
        String bob   = createUser();

        post("/users/" + alice + "/deposits", "{\"amount\":\"100.00\",\"currency\":\"USD\"}");
        post("/users/" + bob   + "/deposits", "{\"amount\":\"50.00\",\"currency\":\"USD\"}");
        post("/users/" + alice + "/transfers",
            "{\"toUserId\":\"" + bob + "\",\"amount\":\"5.00\",\"currency\":\"USD\"}");

        String quoteBody = post("/users/" + alice + "/fx/quotes",
            "{\"sellCurrency\":\"USD\",\"buyCurrency\":\"ARS\",\"sellAmount\":\"10.00\"}");
        String quoteId = objectMapper.readTree(quoteBody).get("quoteId").asText();
        post("/users/" + alice + "/fx/exchanges", "{\"quoteId\":\"" + quoteId + "\"}");

        ResponseEntity<String> scrapeResponse = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(scrapeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String scrape = scrapeResponse.getBody();

        assertThat(scrape).contains("wallet_money_flow_total");
        assertThat(scrape).contains("wallet_provider_health_total");
        assertThat(scrape).contains("operation=\"deposit\",outcome=\"success\"");
        assertThat(scrape).contains("operation=\"transfer\",outcome=\"success\"");
        assertThat(scrape).contains("operation=\"fx_exchange\",outcome=\"success\"");
        assertThat(scrape).contains("outcome=\"request\",provider=\"fx\"");
    }

    private String createUser() throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/users", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        return body.get("userId").asText();
    }

    private String post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(path, new HttpEntity<>(json, headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }
}
