package com.wallet.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SellBuyRate")
class SellBuyRateTest {

    @Test
    @DisplayName("Should reject when sell and buy currencies are the same")
    void ofRejectsSameSellAndBuy() {
        assertThatThrownBy(() -> SellBuyRate.of(SupportedCurrency.USD, SupportedCurrency.USD, BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sell and buy must differ");
    }

    @Test
    @DisplayName("Should reject null rate")
    void constructorRejectsNullRate() {
        assertThatThrownBy(() -> SellBuyRate.of(SupportedCurrency.USD, SupportedCurrency.ARS, null))
            .isInstanceOf(NullPointerException.class);
    }
}
