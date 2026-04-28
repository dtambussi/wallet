package com.wallet.domain.idempotency;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Value object: stored on ledger rows to bind an idempotency key to a canonical request body. */
public record IdempotencyFingerprint(String value) {

    private static final char SEP = '\u001f';

    public IdempotencyFingerprint {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("fingerprint value must be non-blank");
        }
    }

    public static IdempotencyFingerprint ofDepositOrWithdraw(BigDecimal amount, String currencyCode) {
        return new IdempotencyFingerprint(
            sha256Hex(
                amount.stripTrailingZeros().toPlainString() + SEP + currencyCode.toUpperCase()
            )
        );
    }

    public static IdempotencyFingerprint ofTransfer(
        UUID toUserId, BigDecimal amount, String fromCurrencyCode, String toCurrencyCode
    ) {
        return new IdempotencyFingerprint(
            sha256Hex(
                toUserId.toString() + SEP
                + amount.stripTrailingZeros().toPlainString() + SEP
                + fromCurrencyCode.toUpperCase() + SEP
                + toCurrencyCode.toUpperCase()
            )
        );
    }

    public static IdempotencyFingerprint ofFxExchange(UUID quoteId) {
        return new IdempotencyFingerprint(sha256Hex(quoteId.toString()));
    }

    /** Fingerprints a variable-length list of canonical line strings (sorted before hashing so order doesn't matter). */
    public static IdempotencyFingerprint ofLines(List<String> canonicalLines) {
        String sorted = canonicalLines.stream().sorted().collect(java.util.stream.Collectors.joining(String.valueOf(SEP)));
        return new IdempotencyFingerprint(sha256Hex(sorted));
    }

    private static String sha256Hex(String canonicalPayload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException(noSuchAlgorithmException);
        }
    }
}
