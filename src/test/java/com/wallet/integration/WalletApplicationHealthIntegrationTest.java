package com.wallet.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.wallet.integration.base.PostgresIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Full boot on a random port with Testcontainers Postgres; asserts context start and {@code /actuator/health}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Application health (integration)")
class WalletApplicationHealthIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Test
    @DisplayName("Spring context loads with database")
    void contextLoads() {
        // If this runs, the application context started successfully.
    }

    @Test
    @DisplayName("Actuator health endpoint responds OK")
    void actuatorHealthIsAvailable() {
        ResponseEntity<String> response = testRestTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotBlank();
    }
}
