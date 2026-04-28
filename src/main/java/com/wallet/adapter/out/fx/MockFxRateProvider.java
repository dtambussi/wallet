package com.wallet.adapter.out.fx;

import com.wallet.application.port.out.FxRateProvider;
import com.wallet.domain.FxRateSnapshot;
import com.wallet.domain.SellBuyRate;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.money.MoneyScale;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Mock FX for tests and demos (not a live market).
 *
 * 1. The map = how many units of each currency for US$1. USD in the table is 1.
 * 2. This class defines ARS=1390 and BRL=5.15: each is "that many units for US$1" in the mock, same idea for both.
 * 3. 5.15/1390 = 0.003705035971223022 reais per one ARS (divide with shared money scale policy (18dp, HALF_EVEN), same as rate()).
 *    200 ARS: 0.741007194244604400 BRL (200 * that rate, product also to 18dp).
 * 4. In general, what you get in the buy = X * rate(sellCurrency, buyCurrency) where rate is
 *    (buy row) / (sell row). Any pair, same.
 */
@Component
public class MockFxRateProvider implements FxRateProvider {

    private final Map<SupportedCurrency, BigDecimal> buyUnitsPerUsd = new EnumMap<>(SupportedCurrency.class);

    public MockFxRateProvider() {
        buyUnitsPerUsd.put(SupportedCurrency.USD, BigDecimal.ONE);
        // each value: how many of that unit per $1; demo-only
        buyUnitsPerUsd.put(SupportedCurrency.ARS, new BigDecimal("1390"));
        buyUnitsPerUsd.put(SupportedCurrency.CLP, new BigDecimal("895"));
        buyUnitsPerUsd.put(SupportedCurrency.BOB, new BigDecimal("6.91"));
        buyUnitsPerUsd.put(SupportedCurrency.BRL, new BigDecimal("5.15"));
    }

    /**
     * Units of the buy per one unit of the sell: multiply a sell amount by this to get the buy amount. This
     * mock uses (buy per US$1) / (sell per US$1) from the class table.
     * e.g. rate(ARS, BRL) = 5.15/1390 = 0.003705035971223022 reais per one peso (5.15/1390 with shared money scale policy).
     */
    public static final String PROVIDER_ID = "MockFx";

    @Override
    public FxRateSnapshot rate(SupportedCurrency sellCurrency, SupportedCurrency buyCurrency) {
        // (buy per $1) / (sell per $1); SellBuyRate rejects sell == buy
        BigDecimal buyCurrencyUnitsPerOneUsd = buyUnitsPerUsd.get(buyCurrency);
        BigDecimal sellCurrencyUnitsPerOneUsd = buyUnitsPerUsd.get(sellCurrency);
        BigDecimal buyUnitsPerOneSell = MoneyScale.divide(buyCurrencyUnitsPerOneUsd, sellCurrencyUnitsPerOneUsd);
        SellBuyRate r = SellBuyRate.of(sellCurrency, buyCurrency, buyUnitsPerOneSell);
        return FxRateSnapshot.live(r, Instant.now(), PROVIDER_ID);
    }

    /**
     * How much you get in the buy currency for a given sell-amount:
     * sellAmount * rate(sellCurrency, buyCurrency).
     * e.g. 200 ARS to BRL: 200 * (5.15/1390) BRL in this mock.
     */
    public BigDecimal convertBuyAmount(
        SupportedCurrency sellCurrency, SupportedCurrency buyCurrency, BigDecimal sellAmount) {
        if (sellAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("sellAmount must be positive");
        }
        return MoneyScale.round(sellAmount.multiply(rate(sellCurrency, buyCurrency).buyUnitsPerOneSell()));
    }
}
