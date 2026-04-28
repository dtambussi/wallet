package com.wallet.infrastructure.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runtime killswitches for FX and withdrawal operations, tunable via the runtime_config table
 * without redeployment. Both default to enabled (true) when the key is absent.
 *
 * <p>Intended for deliberate operator decisions: planned maintenance windows, prolonged provider
 * outages, or compliance holds. This is distinct from the circuit breaker, which reacts
 * automatically to observed failures — the killswitch gives ops explicit control regardless of
 * whether failures have occurred.
 *
 * <p>To disable FX operations:
 * {@code INSERT INTO runtime_config(key,value) VALUES('wallet.fx.enabled','false')
 *   ON CONFLICT(key) DO UPDATE SET value='false';}
 *
 * <p>To disable withdrawal operations:
 * {@code INSERT INTO runtime_config(key,value) VALUES('wallet.withdrawals.enabled','false')
 *   ON CONFLICT(key) DO UPDATE SET value='false';}
 *
 * <p>To re-enable, set the value back to {@code 'true'} or delete the row. Takes effect within
 * 5 seconds (cache refresh window).
 */
@Component
public class OperationalSwitchPolicy {

    private static final Logger log = LoggerFactory.getLogger(OperationalSwitchPolicy.class);
    private static final String FX_ENABLED_KEY = "wallet.fx.enabled";
    private static final String WITHDRAWALS_ENABLED_KEY = "wallet.withdrawals.enabled";
    private static final Duration REFRESH_EVERY = Duration.ofSeconds(5);

    private final NamedParameterJdbcTemplate jdbc;

    private volatile boolean cachedFxEnabled = true;
    private volatile boolean cachedWithdrawalsEnabled = true;
    private volatile Instant nextRefreshAt = Instant.EPOCH;

    public OperationalSwitchPolicy(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isFxEnabled() {
        refreshIfNeeded();
        return cachedFxEnabled;
    }

    public boolean isWithdrawalsEnabled() {
        refreshIfNeeded();
        return cachedWithdrawalsEnabled;
    }

    private void refreshIfNeeded() {
        if (Instant.now().isBefore(nextRefreshAt)) {
            return;
        }
        synchronized (this) {
            if (Instant.now().isBefore(nextRefreshAt)) {
                return;
            }
            cachedFxEnabled = loadBooleanKey(FX_ENABLED_KEY);
            cachedWithdrawalsEnabled = loadBooleanKey(WITHDRAWALS_ENABLED_KEY);
            nextRefreshAt = Instant.now().plus(REFRESH_EVERY);
        }
    }

    private boolean loadBooleanKey(String key) {
        String raw = jdbc.query(
            "SELECT value FROM runtime_config WHERE key = :key",
            Map.of("key", key),
            rs -> rs.next() ? rs.getString("value") : null
        );
        if (raw == null) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        log.warn("runtime_config {}={} is not a valid boolean; defaulting to enabled", key, raw);
        return true;
    }
}
