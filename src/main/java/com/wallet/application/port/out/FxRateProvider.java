package com.wallet.application.port.out;

import com.wallet.domain.FxRateSnapshot;
import com.wallet.domain.SupportedCurrency;

public interface FxRateProvider {

    /**
     * A {@link FxRateSnapshot} (sell/buy, factor, as-of, source, stale flag) so callers can persist audit fields.
     * <p>
     * e.g. sell ARS, buy BRL: 5.15/1390 = 0.003705… buy units per one sell. Sell and buy must differ.
     */
    FxRateSnapshot rate(SupportedCurrency sellCurrency, SupportedCurrency buyCurrency);
}
