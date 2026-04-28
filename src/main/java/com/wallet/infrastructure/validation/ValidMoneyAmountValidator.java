package com.wallet.infrastructure.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

public class ValidMoneyAmountValidator implements ConstraintValidator<ValidMoneyAmount, BigDecimal> {

    private static final int MAX_FRACTION_DIGITS = 18;
    private static final int MAX_TOTAL_DIGITS = 38;

    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (value.scale() > MAX_FRACTION_DIGITS) {
            return false;
        }
        return value.precision() <= MAX_TOTAL_DIGITS;
    }
}
