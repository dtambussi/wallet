package com.wallet.infrastructure.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates a monetary amount with wallet-wide limits:
 * value must be > 0, with at most 18 fractional digits and at most 38 total digits.
 */
@Documented
@Constraint(validatedBy = ValidMoneyAmountValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidMoneyAmount {

    String message() default "must be positive with at most 18 decimal places";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
