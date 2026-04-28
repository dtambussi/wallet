package com.wallet.infrastructure.idempotency;

public final class IdempotencyFingerprintPolicy {

    private IdempotencyFingerprintPolicy() {}

    /**
     * Empty stored fingerprint means a legacy row (pre-fingerprint column); replays are accepted.
     */
    public static boolean storedMatchesRequest(String storedFingerprint, String fingerprint) {
        if (storedFingerprint == null || storedFingerprint.isEmpty()) {
            return true;
        }
        return storedFingerprint.equals(fingerprint);
    }
}
