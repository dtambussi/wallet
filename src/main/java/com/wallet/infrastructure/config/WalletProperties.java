package com.wallet.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Maps `wallet.*` keys from `application.yml`/env vars into typed Java records.
 * Key mapping reminder:
 * - `wallet.fx.quote-ttl-seconds` -> `fx.quoteTtlSeconds`
 * - `wallet.fx.stale-rate-ttl-seconds` -> `fx.staleRateTtlSeconds`
 * - `wallet.payout.max-attempts` -> `payout.maxAttempts`
 * - `wallet.payout.worker-interval-ms` -> `payout.workerIntervalMs`
 * - `wallet.payout.worker-initial-delay-ms` -> `payout.workerInitialDelayMs`
 * - `wallet.payout.backoff-base-seconds` -> `payout.backoffBaseSeconds`
 */
@ConfigurationProperties(prefix = "wallet")
public record WalletProperties(Fx fx, Payout payout) {

    /** FX config under `wallet.fx.*`. */
    public record Fx(int quoteTtlSeconds, int staleRateTtlSeconds) {}

    /** Payout worker config under `wallet.payout.*`. */
    public record Payout(int maxAttempts, long workerIntervalMs, long workerInitialDelayMs, int backoffBaseSeconds) {}
}
