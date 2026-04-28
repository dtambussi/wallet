package com.wallet.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A priced FX result: the sell/buy rate, a wall-clock as-of, a short source id (e.g. MockFx), and whether the
 * value came from a last-known cache after the provider path failed.
 */
public record FxRateSnapshot(
    SellBuyRate sellBuyRate,
    Instant pricedAt,
    String source,
    boolean servedFromStaleCache
) {

    public FxRateSnapshot {
        Objects.requireNonNull(sellBuyRate, "sellBuyRate");
        Objects.requireNonNull(pricedAt, "pricedAt");
        Objects.requireNonNull(source, "source");
    }

    public static FxRateSnapshot live(SellBuyRate sellBuyRate, Instant pricedAt, String source) {
        return new FxRateSnapshot(sellBuyRate, pricedAt, source, false);
    }

    /**
     * Same numbers and as-of as the last successful fetch, but marks that the provider path failed
     * and the caller is seeing a last-known value.
     */
    public FxRateSnapshot withServedFromStale() {
        if (servedFromStaleCache) {
            return this;
        }
        return new FxRateSnapshot(sellBuyRate, pricedAt, source, true);
    }

    public BigDecimal buyUnitsPerOneSell() {
        return sellBuyRate.buyUnitsPerOneSell();
    }
}
