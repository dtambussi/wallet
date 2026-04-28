package com.wallet.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared money precision policy for this challenge:
 * scale 18 with HALF_EVEN rounding.
 */
public final class MoneyScale {

    public static final int SCALE = 18;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    private MoneyScale() {}

    public static BigDecimal round(BigDecimal amount) {
        return amount.setScale(SCALE, ROUNDING_MODE);
    }

    public static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, SCALE, ROUNDING_MODE);
    }
}
