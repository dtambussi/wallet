package com.wallet.infrastructure.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runtime policy for FX behavior that can be tuned without redeploying.
 * Values are loaded from runtime_config with a short in-memory refresh window.
 */
@Component
public class FxRuntimePolicy {

    private static final Logger log = LoggerFactory.getLogger(FxRuntimePolicy.class);
    private static final String STALE_TTL_KEY = "wallet.fx.stale-rate-ttl-seconds";
    private static final Duration REFRESH_EVERY = Duration.ofSeconds(5);

    private final NamedParameterJdbcTemplate jdbc;
    private final Duration fallbackStaleRateTtl;

    private volatile Duration cachedStaleRateTtl;
    private volatile Instant nextRefreshAt = Instant.EPOCH;

    public FxRuntimePolicy(NamedParameterJdbcTemplate jdbc, WalletProperties walletProperties) {
        this.jdbc = jdbc;
        this.fallbackStaleRateTtl = Duration.ofSeconds(walletProperties.fx().staleRateTtlSeconds());
        this.cachedStaleRateTtl = fallbackStaleRateTtl;
    }

    public Duration staleRateTtl() {
        if (Instant.now().isBefore(nextRefreshAt)) {
            return cachedStaleRateTtl;
        }
        synchronized (this) {
            if (Instant.now().isBefore(nextRefreshAt)) {
                return cachedStaleRateTtl;
            }
            cachedStaleRateTtl = loadStaleRateTtl();
            nextRefreshAt = Instant.now().plus(REFRESH_EVERY);
            return cachedStaleRateTtl;
        }
    }

    private Duration loadStaleRateTtl() {
        String raw = jdbc.query(
            "SELECT value FROM runtime_config WHERE key = :key",
            Map.of("key", STALE_TTL_KEY),
            rs -> rs.next() ? rs.getString("value") : null
        );
        if (raw == null) {
            return fallbackStaleRateTtl;
        }
        try {
            int seconds = Integer.parseInt(raw);
            if (seconds < 0) {
                log.warn("runtime_config {}={} is negative; using fallback {}", STALE_TTL_KEY, raw, fallbackStaleRateTtl);
                return fallbackStaleRateTtl;
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            log.warn("runtime_config {}={} is invalid integer; using fallback {}", STALE_TTL_KEY, raw, fallbackStaleRateTtl);
            return fallbackStaleRateTtl;
        }
    }
}
