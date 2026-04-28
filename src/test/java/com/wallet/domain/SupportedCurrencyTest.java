package com.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SupportedCurrency")
class SupportedCurrencyTest {

    @Test
    @DisplayName("Should resolve known ISO codes case-insensitively")
    void fromCodeAcceptsUpperAndLowerCase() {
        assertThat(SupportedCurrency.fromCode("usd")).isEqualTo(SupportedCurrency.USD);
        assertThat(SupportedCurrency.fromCode("BRL")).isEqualTo(SupportedCurrency.BRL);
    }

    @Test
    @DisplayName("Should reject null, wrong length, or unknown codes")
    void fromCodeRejectsInvalidInputs() {
        assertThatThrownBy(() -> SupportedCurrency.fromCode(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupportedCurrency.fromCode("US")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupportedCurrency.fromCode("EUR"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported currency");
    }
}
