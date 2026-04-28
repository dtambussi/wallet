package com.wallet.infrastructure.log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class LogSanitizer {

    private LogSanitizer() {}

    /** 8-hex prefix of SHA-256, for log correlation without echoing long secrets. */
    public static String shortHash(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes, 0, 4);
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            return "hash-err";
        }
    }
}
