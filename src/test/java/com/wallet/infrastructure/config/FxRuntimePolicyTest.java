package com.wallet.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("FX Runtime Policy")
class FxRuntimePolicyTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private FxRuntimePolicy policy;

    private static final WalletProperties PROPS = new WalletProperties(
        new WalletProperties.Fx(30, 0),
        new WalletProperties.Payout(3, 60000, 3600000, 2)
    );

    @BeforeEach
    void setUp() {
        policy = new FxRuntimePolicy(jdbc, PROPS);
    }

    @SuppressWarnings("unchecked")
    private void givenDbReturns(String value) {
        when(jdbc.query(anyString(), anyMap(), any(ResultSetExtractor.class))).thenReturn(value);
    }

    @Test
    @DisplayName("Should return Duration from valid DB value")
    void returnsDbValueWhenValid() {
        givenDbReturns("30");

        assertThat(policy.staleRateTtl()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("Should fall back to WalletProperties when DB returns null (key missing)")
    void fallsBackToPropertiesWhenDbReturnsNull() {
        givenDbReturns(null);

        assertThat(policy.staleRateTtl()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Should fall back to WalletProperties when DB value is not a valid integer")
    void fallsBackWhenDbValueIsNotInteger() {
        givenDbReturns("not-a-number");

        assertThat(policy.staleRateTtl()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Should fall back to WalletProperties when DB value is negative")
    void fallsBackWhenDbValueIsNegative() {
        givenDbReturns("-5");

        assertThat(policy.staleRateTtl()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Should return zero when DB value is zero")
    void returnsZeroForZeroDbValue() {
        givenDbReturns("0");

        assertThat(policy.staleRateTtl()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Should cache result within the 5-second refresh window and not re-query DB")
    @SuppressWarnings("unchecked")
    void cachesResultWithinRefreshWindow() {
        when(jdbc.query(anyString(), anyMap(), any(ResultSetExtractor.class))).thenReturn("10");

        // First call triggers DB query
        Duration first = policy.staleRateTtl();
        // Second call within the cache window should NOT re-query
        Duration second = policy.staleRateTtl();

        assertThat(first).isEqualTo(second).isEqualTo(Duration.ofSeconds(10));
        verify(jdbc, times(1)).query(anyString(), anyMap(), any(ResultSetExtractor.class));
    }
}
