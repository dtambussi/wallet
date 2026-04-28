package com.wallet.adapter.out.fx;

import com.wallet.application.port.out.FxRateProvider;
import com.wallet.domain.FxRateSnapshot;
import com.wallet.domain.SupportedCurrency;
import com.wallet.infrastructure.exception.FxRateUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resilience4j circuit breaker in front of the real FX provider. On success, we keep the last quoted rate
 * and a timestamp, per sell/buy pair, for later if the line goes bad.
 * <p>
 * Happy path: call through, save rate and time, return the rate.
 * <p>
 * Failure path (delegate error while CLOSED or HALF_OPEN, or call rejected while OPEN), step by step:
 * <p>
 * 1) If stale window is 0, the default (stale-rate-ttl-seconds in config). Do not return a stored rate. Throw
 *    FxRateUnavailableException. This is the conservative wallet behavior.
 * <p>
 * 2) If stale window is not 0. If we have a stored rate, and it is younger than that window, return that rate
 *    and log a warning. This can be useful if provider fails too much and rates stay constant for that ttl.
 * <p>
 * 3) Otherwise, same outcome as 1) (nothing cached, or cache too old).
 * <p>
 * Note: the circuit breaker's 30s open state is a separate idea from the stale-ttl; they are not the same
 * setting.
 * <p>
 * <b>Scaling note:</b> the rate cache is per-JVM. With multiple service instances each one calls the
 * provider independently and warms its own map. For multi-instance deployments the production replacement
 * is a shared rate store (e.g. Redis) populated by a dedicated rate-refresher job on a fixed schedule,
 * with this class reading from that store instead of calling the provider on every request.
 */
public class ResilientFxRateProvider implements FxRateProvider {

    private static final Logger log = LoggerFactory.getLogger(ResilientFxRateProvider.class);

    private final FxRateProvider delegate;
    private final CircuitBreaker circuitBreaker;
    // supplier for stale-rate-ttl-seconds
    private final Supplier<Duration> staleRateTtlSupplier;
    private final Counter fxProviderRequests;
    private final Counter fxProviderDegraded;
    private final Counter fxProviderDegradedServedStale;
    private final ConcurrentHashMap<String, FxRateSnapshot> rateCache = new ConcurrentHashMap<>();

    public ResilientFxRateProvider(FxRateProvider delegate, Duration staleRateTtl) {
        this(delegate, () -> staleRateTtl, new SimpleMeterRegistry());
    }

    public ResilientFxRateProvider(FxRateProvider delegate, Supplier<Duration> staleRateTtlSupplier) {
        this(delegate, staleRateTtlSupplier, new SimpleMeterRegistry());
    }

    public ResilientFxRateProvider(
        FxRateProvider delegate,
        Supplier<Duration> staleRateTtlSupplier,
        MeterRegistry meterRegistry
    ) {
        this(delegate, staleRateTtlSupplier, meterRegistry, CircuitBreakerConfig.custom()
            .slidingWindowSize(5)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(2)
            .build());
    }

    /** Package-private: allows tests to inject a fast-recovery {@link CircuitBreakerConfig}. */
    ResilientFxRateProvider(
        FxRateProvider delegate,
        Supplier<Duration> staleRateTtlSupplier,
        MeterRegistry meterRegistry,
        CircuitBreakerConfig circuitBreakerConfig
    ) {
        this.delegate = delegate;
        this.staleRateTtlSupplier = staleRateTtlSupplier;
        this.circuitBreaker = CircuitBreaker.of("fxRate", circuitBreakerConfig);
        this.fxProviderRequests = Counter.builder("wallet_provider_health_total")
            .description("Provider health outcomes by provider and outcome")
            .tag("provider", "fx")
            .tag("outcome", "request")
            .register(meterRegistry);
        this.fxProviderDegraded = Counter.builder("wallet_provider_health_total")
            .description("Provider health outcomes by provider and outcome")
            .tag("provider", "fx")
            .tag("outcome", "degraded")
            .register(meterRegistry);
        this.fxProviderDegradedServedStale = Counter.builder("wallet_provider_health_total")
            .description("Provider health outcomes by provider and outcome")
            .tag("provider", "fx")
            .tag("outcome", "degraded_served_stale")
            .register(meterRegistry);
    }

    @Override
    public FxRateSnapshot rate(SupportedCurrency sellCurrency, SupportedCurrency buyCurrency) {
        fxProviderRequests.increment();
        // Cache key is directional (sell -> buy), not symmetric.
        // e.g. sell=USD, buy=ARS => "USD_ARS" (different from "ARS_USD").
        String sellBuyKey = sellCurrency.name() + "_" + buyCurrency.name();
        try {
            FxRateSnapshot liveRateSnapshot = circuitBreaker.executeSupplier(
                () -> delegate.rate(sellCurrency, buyCurrency));
            rateCache.put(sellBuyKey, liveRateSnapshot);
            return liveRateSnapshot;
        } catch (Exception e) {
            return resolveFailureWithStalePolicy(sellBuyKey, sellCurrency, buyCurrency, e);
        }
    }

    private FxRateSnapshot resolveFailureWithStalePolicy(
        String sellBuyKey,
        SupportedCurrency sellCurrency,
        SupportedCurrency buyCurrency,
        Exception cause
    ) {
        fxProviderDegraded.increment();
        Duration staleRateTtl = staleRateTtlSupplier.get();
        if (!staleRateTtl.isZero()) { // config says we are open to a recent "stale" value
            FxRateSnapshot cachedRateSnapshot = rateCache.get(sellBuyKey);
            if (cachedRateSnapshot != null && cachedRateSnapshot.pricedAt().isAfter(Instant.now().minus(staleRateTtl))) {
                log.warn("FX provider unavailable ({}) — serving cached rate for {}/{} (age < {}s)",
                    cause.getMessage(), sellCurrency, buyCurrency, staleRateTtl.toSeconds());
                fxProviderDegradedServedStale.increment();
                return cachedRateSnapshot.withServedFromStale();
            }
        }
        throw new FxRateUnavailableException(
            "FX rate provider unavailable for " + sellCurrency + "/" + buyCurrency
                + (staleRateTtl.isZero() ? "" : " and no recent cached rate exists"), cause);
    }
}
