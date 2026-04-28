package com.wallet.adapter.out.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wallet.application.port.out.FxRateProvider;
import com.wallet.domain.FxRateSnapshot;
import com.wallet.domain.SellBuyRate;
import com.wallet.domain.SupportedCurrency;
import com.wallet.infrastructure.exception.FxRateUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Resilient FX Rate Provider")
class ResilientFxRateProviderTest {

    private static final SupportedCurrency SELL_CURRENCY = SupportedCurrency.ARS;
    private static final SupportedCurrency BUY_CURRENCY = SupportedCurrency.BRL;
    private static final SellBuyRate ARS_TO_BRL_RATE =
        SellBuyRate.of(SELL_CURRENCY, BUY_CURRENCY, new BigDecimal("0.1"));

    @Test
    @DisplayName("Should throw when delegate fails and stale TTL is zero")
    void whenDelegateAlwaysFails_staleTtlZero_throws() {
        FxRateProvider alwaysFail =
            (sellCurrency, buyCurrency) -> { throw new RuntimeException("unavailable"); };
        ResilientFxRateProvider resilient = new ResilientFxRateProvider(alwaysFail, Duration.ZERO);
        assertThatThrownBy(() -> resilient.rate(SELL_CURRENCY, BUY_CURRENCY))
            .isInstanceOf(FxRateUnavailableException.class);
    }

    @Test
    @DisplayName("Should throw when delegate fails and no stale cache exists")
    void whenDelegateAlwaysFails_staleTtlPositive_stillNoCachedQuote_throws() {
        FxRateProvider alwaysFail =
            (sellCurrency, buyCurrency) -> { throw new RuntimeException("unavailable"); };
        ResilientFxRateProvider resilient = new ResilientFxRateProvider(alwaysFail, Duration.ofMinutes(1));
        assertThatThrownBy(() -> resilient.rate(SELL_CURRENCY, BUY_CURRENCY))
            .isInstanceOf(FxRateUnavailableException.class);
    }

    @Test
    @DisplayName("Should not serve cached rate after failure when stale TTL is zero")
    void afterOneSuccess_onFailure_staleTtlZero_doesNotServeCache() {
        FailingOnSecondCall delegate = new FailingOnSecondCall(ARS_TO_BRL_RATE);
        ResilientFxRateProvider resilient = new ResilientFxRateProvider(delegate, Duration.ZERO);
        assertThat(resilient.rate(SELL_CURRENCY, BUY_CURRENCY).sellBuyRate()).isEqualTo(ARS_TO_BRL_RATE);
        assertThatThrownBy(() -> resilient.rate(SELL_CURRENCY, BUY_CURRENCY))
            .isInstanceOf(FxRateUnavailableException.class);
    }

    @Test
    @DisplayName("Should serve cached rate after failure when cache is within stale TTL")
    void afterOneSuccess_onFailure_staleTtlPositive_servesCacheWithinTtl() {
        FailingOnSecondCall delegate = new FailingOnSecondCall(ARS_TO_BRL_RATE);
        ResilientFxRateProvider resilient = new ResilientFxRateProvider(delegate, Duration.ofMinutes(1));
        FxRateSnapshot snapshotFromLiveDelegate = resilient.rate(SELL_CURRENCY, BUY_CURRENCY);
        FxRateSnapshot snapshotFromStaleCacheAfterFailure = resilient.rate(SELL_CURRENCY, BUY_CURRENCY);
        assertThat(snapshotFromLiveDelegate.sellBuyRate()).isEqualTo(ARS_TO_BRL_RATE);
        assertThat(snapshotFromStaleCacheAfterFailure.sellBuyRate()).isEqualTo(ARS_TO_BRL_RATE);
        assertThat(snapshotFromLiveDelegate.pricedAt()).isEqualTo(snapshotFromStaleCacheAfterFailure.pricedAt());
        assertThat(snapshotFromLiveDelegate.servedFromStaleCache()).isFalse();
        assertThat(snapshotFromStaleCacheAfterFailure.servedFromStaleCache()).isTrue();
    }

    @Test
    @DisplayName("Should expire cached rate when stale TTL window passes")
    void afterOneSuccess_onFailure_staleTtlShort_afterWait_cacheExpired() throws Exception {
        FailingOnSecondCall delegate = new FailingOnSecondCall(ARS_TO_BRL_RATE);
        ResilientFxRateProvider resilient = new ResilientFxRateProvider(delegate, Duration.ofMillis(100));
        assertThat(resilient.rate(SELL_CURRENCY, BUY_CURRENCY).sellBuyRate()).isEqualTo(ARS_TO_BRL_RATE);
        Thread.sleep(200);
        assertThatThrownBy(() -> resilient.rate(SELL_CURRENCY, BUY_CURRENCY))
            .isInstanceOf(FxRateUnavailableException.class);
    }

    @Test
    @DisplayName("Should apply runtime stale policy changes without recreating provider")
    void stalePolicyCanChangeAtRuntime_withoutRecreatingProvider() {
        FailingOnSecondCall delegate = new FailingOnSecondCall(ARS_TO_BRL_RATE);
        AtomicReference<Duration> staleTtl = new AtomicReference<>(Duration.ZERO);
        ResilientFxRateProvider resilient = new ResilientFxRateProvider(delegate, staleTtl::get);

        assertThat(resilient.rate(SELL_CURRENCY, BUY_CURRENCY).sellBuyRate()).isEqualTo(ARS_TO_BRL_RATE);
        assertThatThrownBy(() -> resilient.rate(SELL_CURRENCY, BUY_CURRENCY))
            .isInstanceOf(FxRateUnavailableException.class);

        staleTtl.set(Duration.ofMinutes(1));
        assertThat(resilient.rate(SELL_CURRENCY, BUY_CURRENCY).sellBuyRate()).isEqualTo(ARS_TO_BRL_RATE);
    }

    @Test
    @DisplayName("Should track provider health counters for requests and degraded paths")
    void providerHealthCounters_trackRequestsAndDegradedPaths() {
        FailingOnSecondCall delegate = new FailingOnSecondCall(ARS_TO_BRL_RATE);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ResilientFxRateProvider resilient = new ResilientFxRateProvider(
            delegate, () -> Duration.ofMinutes(1), meterRegistry);

        resilient.rate(SELL_CURRENCY, BUY_CURRENCY);
        resilient.rate(SELL_CURRENCY, BUY_CURRENCY);

        assertThat(meterRegistry.get("wallet_provider_health_total")
            .tag("provider", "fx")
            .tag("outcome", "request")
            .counter().count()).isEqualTo(2.0d);
        assertThat(meterRegistry.get("wallet_provider_health_total")
            .tag("provider", "fx")
            .tag("outcome", "degraded")
            .counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("wallet_provider_health_total")
            .tag("provider", "fx")
            .tag("outcome", "degraded_served_stale")
            .counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("Circuit breaker opens after sliding window fills with failures — delegate not called on rejected request")
    void circuitBreaker_opensAfterSlidingWindowFails_delegateNotCalledOnRejectedRequest() {
        AtomicInteger delegateCallCount = new AtomicInteger(0);
        FxRateProvider countingFailDelegate = (sell, buy) -> {
            delegateCallCount.incrementAndGet();
            throw new RuntimeException("provider-down");
        };
        // Default config: slidingWindowSize=5, failureRateThreshold=50%
        ResilientFxRateProvider resilient = new ResilientFxRateProvider(countingFailDelegate, Duration.ZERO);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> resilient.rate(SELL_CURRENCY, BUY_CURRENCY))
                .isInstanceOf(FxRateUnavailableException.class);
        }
        assertThat(delegateCallCount.get()).isEqualTo(5);

        // CB is now OPEN — 6th call must be rejected without reaching the delegate
        assertThatThrownBy(() -> resilient.rate(SELL_CURRENCY, BUY_CURRENCY))
            .isInstanceOf(FxRateUnavailableException.class);
        assertThat(delegateCallCount.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("Circuit breaker recovers full cycle: OPEN → HALF_OPEN → CLOSED after waitDuration passes")
    void circuitBreaker_recoversCycle_openToHalfOpenToClosed() throws InterruptedException {
        AtomicBoolean shouldFail = new AtomicBoolean(true);
        FxRateProvider toggleDelegate = (sell, buy) -> {
            if (shouldFail.get()) throw new RuntimeException("provider-down");
            return FxRateSnapshot.live(ARS_TO_BRL_RATE, Instant.now(), "test");
        };
        CircuitBreakerConfig fastConfig = CircuitBreakerConfig.custom()
            .slidingWindowSize(5)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofMillis(100))
            .permittedNumberOfCallsInHalfOpenState(2)
            .build();
        ResilientFxRateProvider resilient = new ResilientFxRateProvider(
            toggleDelegate, () -> Duration.ZERO, new SimpleMeterRegistry(), fastConfig);

        // Drive CB to OPEN with 5 consecutive failures
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> resilient.rate(SELL_CURRENCY, BUY_CURRENCY))
                .isInstanceOf(FxRateUnavailableException.class);
        }

        // Wait for CB to transition from OPEN to HALF_OPEN
        Thread.sleep(200);
        shouldFail.set(false);

        // 2 successful calls in HALF_OPEN close the circuit
        assertThat(resilient.rate(SELL_CURRENCY, BUY_CURRENCY).sellBuyRate()).isEqualTo(ARS_TO_BRL_RATE);
        assertThat(resilient.rate(SELL_CURRENCY, BUY_CURRENCY).sellBuyRate()).isEqualTo(ARS_TO_BRL_RATE);

        // CB is now CLOSED — subsequent calls pass through normally
        assertThat(resilient.rate(SELL_CURRENCY, BUY_CURRENCY).sellBuyRate()).isEqualTo(ARS_TO_BRL_RATE);
    }

    private static final class FailingOnSecondCall implements FxRateProvider {
        private final SellBuyRate onSuccess;
        private int callCount;

        FailingOnSecondCall(SellBuyRate onSuccess) {
            this.onSuccess = onSuccess;
        }

        @Override
        public FxRateSnapshot rate(SupportedCurrency sellCurrency, SupportedCurrency buyCurrency) {
            callCount++;
            if (callCount >= 2) {
                throw new RuntimeException("down");
            }
            return FxRateSnapshot.live(onSuccess, Instant.now(), "test");
        }
    }
}
