package com.wallet.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("Operational Switch Policy")
class OperationalSwitchPolicyTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private OperationalSwitchPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new OperationalSwitchPolicy(jdbc);
    }

    @SuppressWarnings("unchecked")
    private void givenDbReturns(String value) {
        when(jdbc.query(anyString(), anyMap(), any(ResultSetExtractor.class))).thenReturn(value);
    }

    @Test
    @DisplayName("Should return true (enabled) when key is absent from runtime_config")
    void enabledByDefaultWhenKeyAbsent() {
        givenDbReturns(null);

        assertThat(policy.isFxEnabled()).isTrue();
        assertThat(policy.isWithdrawalsEnabled()).isTrue();
    }

    @Test
    @DisplayName("Should return false when runtime_config value is 'false'")
    void returnsFalseWhenValueIsFalse() {
        givenDbReturns("false");

        assertThat(policy.isFxEnabled()).isFalse();
    }

    @Test
    @DisplayName("Should return true when runtime_config value is 'true'")
    void returnsTrueWhenValueIsTrue() {
        givenDbReturns("true");

        assertThat(policy.isFxEnabled()).isTrue();
    }

    @Test
    @DisplayName("Should default to enabled when runtime_config value is not a valid boolean")
    void defaultsToEnabledForInvalidValue() {
        givenDbReturns("yes");

        assertThat(policy.isWithdrawalsEnabled()).isTrue();
    }

    @Test
    @DisplayName("Should cache result within the 5-second refresh window and not re-query DB")
    @SuppressWarnings("unchecked")
    void cachesResultWithinRefreshWindow() {
        when(jdbc.query(anyString(), anyMap(), any(ResultSetExtractor.class))).thenReturn("false");

        boolean first = policy.isFxEnabled();
        boolean second = policy.isFxEnabled();

        assertThat(first).isFalse();
        assertThat(second).isFalse();
        // Both keys are loaded in one refresh cycle, so two DB calls (one per key) on first access
        verify(jdbc, times(2)).query(anyString(), anyMap(), any(ResultSetExtractor.class));
    }
}
