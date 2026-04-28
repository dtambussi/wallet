package com.wallet.domain.idempotency;

import java.util.UUID;

/** Bundles the scoped idempotency key and request fingerprint for one command. */
public record IdempotencyContext(String scopedKey, IdempotencyFingerprint fingerprint) {

    public static IdempotencyContext scopedForUser(
        UUID actingUserId,
        String idempotencyKeyFromHeader,
        IdempotencyFingerprint fingerprint
    ) {
        String suffix = (idempotencyKeyFromHeader == null || idempotencyKeyFromHeader.isBlank())
            ? fingerprint.value()
            : idempotencyKeyFromHeader.trim();
        return new IdempotencyContext(actingUserId + "|" + suffix, fingerprint);
    }
}
