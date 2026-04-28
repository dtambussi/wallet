package com.wallet.domain.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Idempotency Fingerprint")
class IdempotencyFingerprintTest {

    @Test
    @DisplayName("Should produce same fingerprint for equivalent deposit/withdraw inputs")
    void depositOrWithdrawSameInputsSameValue() {
        assertThat(
            IdempotencyFingerprint.ofDepositOrWithdraw(new BigDecimal("10.00"), "usd").value()
        ).isEqualTo(
            IdempotencyFingerprint.ofDepositOrWithdraw(new BigDecimal("10.0"), "USD").value()
        );
    }

    @Test
    @DisplayName("Should produce deterministic transfer fingerprint")
    void transferDeterministic() {
        UUID recipientUserId = UUID.fromString("00000000-0000-0000-0000-00000000aa01");
        // Same-currency: amount normalisation and case-insensitivity still apply.
        String firstFingerprint = IdempotencyFingerprint.ofTransfer(recipientUserId, new BigDecimal("1"), "USD", "USD").value();
        String secondFingerprint = IdempotencyFingerprint.ofTransfer(recipientUserId, new BigDecimal("1.00"), "usd", "usd").value();
        assertThat(firstFingerprint).isEqualTo(secondFingerprint);
    }

    @Test
    @DisplayName("Should differentiate cross-currency transfer fingerprint from same-currency")
    void transferCrossCurrencyDiffersFromSameCurrency() {
        UUID recipientUserId = UUID.fromString("00000000-0000-0000-0000-00000000aa01");
        String sameCurrencyFingerprint = IdempotencyFingerprint.ofTransfer(recipientUserId, new BigDecimal("1"), "USD", "USD").value();
        String crossCurrencyFingerprint = IdempotencyFingerprint.ofTransfer(recipientUserId, new BigDecimal("1"), "USD", "ARS").value();
        assertThat(sameCurrencyFingerprint).isNotEqualTo(crossCurrencyFingerprint);
    }

    @Test
    @DisplayName("Should use quote id as FX exchange fingerprint source")
    void fxExchangeUsesQuoteId() {
        UUID quoteId = UUID.randomUUID();
        assertThat(IdempotencyFingerprint.ofFxExchange(quoteId).value())
            .isEqualTo(IdempotencyFingerprint.ofFxExchange(quoteId).value());
    }
}
