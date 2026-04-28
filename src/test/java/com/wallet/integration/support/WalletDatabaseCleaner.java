package com.wallet.integration.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Truncates mutable wallet tables between integration tests while leaving {@code runtime_config} seeded.
 */
public final class WalletDatabaseCleaner {

    private WalletDatabaseCleaner() {}

    public static void truncateMutableTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
            """
            TRUNCATE TABLE
              ledger_lines,
              ledger_entries,
              balance_projections,
              fx_quotes,
              payout_outbox,
              financial_audit_events,
              users
            RESTART IDENTITY CASCADE
            """
        );
    }
}
