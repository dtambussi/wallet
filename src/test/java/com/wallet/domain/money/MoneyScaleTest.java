package com.wallet.domain.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MoneyScale")
class MoneyScaleTest {

    @Test
    @DisplayName("Should normalize to scale 18 with HALF_EVEN rounding mode")
    void roundUsesHalfEvenAtScale18() {
        BigDecimal rounded = MoneyScale.round(new BigDecimal("42.125"));

        assertThat(rounded.scale()).isEqualTo(18);
        assertThat(MoneyScale.ROUNDING_MODE).isEqualTo(RoundingMode.HALF_EVEN);
        assertThat(rounded).isEqualByComparingTo(new BigDecimal("42.125"));
    }

    @Test
    @DisplayName("Should divide with scale 18 and HALF_EVEN")
    void divideUsesConfiguredScaleAndRounding() {
        BigDecimal quotient = MoneyScale.divide(new BigDecimal("6"), new BigDecimal("2"));

        assertThat(quotient.scale()).isEqualTo(18);
        assertThat(quotient).isEqualByComparingTo(new BigDecimal("3.000000000000000000"));
    }
}
