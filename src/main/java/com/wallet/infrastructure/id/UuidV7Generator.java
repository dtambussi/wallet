package com.wallet.infrastructure.id;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates RFC 9562 UUIDv7 identifiers.
 *
 * <p>IDs are time-ordered (millisecond precision) and monotonic within this JVM,
 * which keeps insertion and pagination order stable when sorting by id.</p>
 */
public final class UuidV7Generator {

    private static final Object LOCK = new Object();

    private static long lastUnixMs = -1L;
    private static int sequence = 0;

    private UuidV7Generator() {}

    public static UUID next() {
        long unixMs = System.currentTimeMillis();
        int randA;

        synchronized (LOCK) {
            if (unixMs > lastUnixMs) {
                lastUnixMs = unixMs;
                sequence = ThreadLocalRandom.current().nextInt(1 << 12);
            } else {
                sequence = (sequence + 1) & 0x0FFF;
                if (sequence == 0) {
                    do {
                        unixMs = System.currentTimeMillis();
                    } while (unixMs <= lastUnixMs);
                    lastUnixMs = unixMs;
                    sequence = ThreadLocalRandom.current().nextInt(1 << 12);
                }
            }
            randA = sequence;
        }

        long msb = ((unixMs & 0xFFFFFFFFFFFFL) << 16)
            | 0x0000000000007000L
            | (randA & 0x0FFFL);

        long randB = ThreadLocalRandom.current().nextLong() & 0x3FFFFFFFFFFFFFFFL;
        long lsb = 0x8000000000000000L | randB;

        return new UUID(msb, lsb);
    }
}
