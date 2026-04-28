package com.wallet.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One quoted FX direction: which currency you sell, which you buy, and {@link #buyUnitsPerOneSell()} — how many
 * units of the buy currency you get for one unit of the sell currency (multiply a sell-amount by it to get the
 * buy-amount in the buy currency).
 */
public record SellBuyRate(SupportedCurrency sell, SupportedCurrency buy, BigDecimal buyUnitsPerOneSell) {

    public SellBuyRate {
        if (sell == buy) {
            throw new IllegalArgumentException("sell and buy must differ");
        }
        Objects.requireNonNull(buyUnitsPerOneSell, "buyUnitsPerOneSell");
    }

    public static SellBuyRate of(SupportedCurrency sell, SupportedCurrency buy, BigDecimal buyUnitsPerOneSell) {
        return new SellBuyRate(sell, buy, buyUnitsPerOneSell);
    }
}
