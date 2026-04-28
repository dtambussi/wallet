package com.wallet.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum SupportedCurrency {
    USD,
    ARS,
    CLP,
    BOB,
    BRL;

    private static final Set<String> CODES = Arrays.stream(values())
        .map(Enum::name)
        .collect(Collectors.toSet());

    public static SupportedCurrency fromCode(String code) {
        if (code == null || code.length() != 3) {
            throw new IllegalArgumentException("Invalid currency code");
        }
        String normalizedCode = code.toUpperCase();
        if (!CODES.contains(normalizedCode)) {
            throw new IllegalArgumentException("Unsupported currency: " + code);
        }
        return valueOf(normalizedCode);
    }
}
