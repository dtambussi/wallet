package com.wallet.infrastructure.config;

import com.wallet.adapter.out.fx.MockFxRateProvider;
import com.wallet.adapter.out.fx.ResilientFxRateProvider;
import com.wallet.application.port.out.FxRateProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class FxProviderConfig {

    @Bean
    @Primary
    public FxRateProvider fxRateProvider(
        MockFxRateProvider mockFxRateProvider,
        FxRuntimePolicy fxRuntimePolicy,
        MeterRegistry meterRegistry
    ) {
        return new ResilientFxRateProvider(mockFxRateProvider, fxRuntimePolicy::staleRateTtl, meterRegistry);
    }
}
