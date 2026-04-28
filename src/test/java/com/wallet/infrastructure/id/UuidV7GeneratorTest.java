package com.wallet.infrastructure.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UUIDv7 Generator")
class UuidV7GeneratorTest {

    @Test
    @DisplayName("Should generate UUID with version 7 and RFC variant")
    void generatedUuidHasVersion7AndRfcVariant() {
        UUID id = UuidV7Generator.next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should generate non-decreasing ids in natural order")
    void generatedIdsAreNonDecreasingWhenSortedNaturally() {
        UUID first = UuidV7Generator.next();
        UUID second = UuidV7Generator.next();

        assertThat(first).isNotEqualTo(second);
        assertThat(first.compareTo(second)).isLessThanOrEqualTo(0);
    }
}
