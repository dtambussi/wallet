package com.wallet.adapter.out.payments;

import com.wallet.application.port.out.WithdrawalProvider;
import com.wallet.domain.SupportedCurrency;
import java.math.BigDecimal;
import java.util.UUID;
import com.wallet.infrastructure.id.UuidV7Generator;
import org.springframework.stereotype.Component;

/**
 * Demo/mock withdrawal provider used by this challenge.
 * <p>
 * What it does:
 * 1) Validates amount > 0.
 * 2) Returns a synthetic provider reference value (no real external API call).
 * <p>
 * Why it exists:
 * - Keeps payout flow deterministic for tests and local runs.
 * - Lets the outbox worker exercise success/failure paths without real network dependencies.
 */
@Component
public class MockWithdrawalProvider implements WithdrawalProvider {

    @Override
    public String initiatePayout(UUID userId, SupportedCurrency currency, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return "mock-payout-" + UuidV7Generator.next();
    }
}
