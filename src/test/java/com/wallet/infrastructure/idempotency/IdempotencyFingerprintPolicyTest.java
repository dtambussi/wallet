package com.wallet.infrastructure.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Idempotency Fingerprint Policy")
class IdempotencyFingerprintPolicyTest {

    @Test
    @DisplayName("Should accept any request when stored fingerprint is empty")
    void emptyStoredAcceptsAnyRequest() {
        assertThat(IdempotencyFingerprintPolicy.storedMatchesRequest("", "abc")).isTrue();
        assertThat(IdempotencyFingerprintPolicy.storedMatchesRequest(null, "abc")).isTrue();
    }

    @Test
    @DisplayName("Should require exact match when stored fingerprint is present")
    void nonEmptyStoredMustEqualRequest() {
        assertThat(IdempotencyFingerprintPolicy.storedMatchesRequest("deadbeef", "deadbeef")).isTrue();
        assertThat(IdempotencyFingerprintPolicy.storedMatchesRequest("deadbeef", "cafebabe")).isFalse();
        assertThat(IdempotencyFingerprintPolicy.storedMatchesRequest("deadbeef", "DEADBEEF")).isFalse();
    }
}
