package com.wallet.application.port.out;

import com.wallet.domain.SupportedCurrency;
import java.math.BigDecimal;
import java.util.UUID;

public interface WithdrawalProvider {

    /**
     * Represents a third-party payout. Returns a provider reference for audit.
     */
    String initiatePayout(UUID userId, SupportedCurrency currency, BigDecimal amount);
}
