package com.wallet.adapter.out.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wallet.domain.FxRateSnapshot;
import com.wallet.domain.SupportedCurrency;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Mock FX Rate Provider")
class MockFxRateProviderTest {

    private final MockFxRateProvider mockFxRateProvider = new MockFxRateProvider();

    @Test
    @DisplayName("Should convert USD to ARS using mock table")
    void usdToArs() {
        FxRateSnapshot rateSnapshot = mockFxRateProvider.rate(SupportedCurrency.USD, SupportedCurrency.ARS);
        BigDecimal buy = mockFxRateProvider.convertBuyAmount(
            SupportedCurrency.USD, SupportedCurrency.ARS, new BigDecimal("100")
        );
        // 100 USD × ~1390 ARS/USD (mock table)
        assertThat(buy.compareTo(new BigDecimal("139000"))).isZero();
        assertThat(rateSnapshot.source()).isEqualTo("MockFx");
        assertThat(rateSnapshot.servedFromStaleCache()).isFalse();
    }

    @Test
    @DisplayName("Should reject same-currency quote requests")
    void sameCurrencyRejected() {
        assertThatThrownBy(() -> mockFxRateProvider.rate(SupportedCurrency.USD, SupportedCurrency.USD))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
