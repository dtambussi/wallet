package com.wallet.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.wallet.domain.ledger.TransactionCursor;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Transaction Cursor Codec")
class TransactionCursorCodecTest {

    @Test
    @DisplayName("Should encode and decode cursor round-trip")
    void roundTrip() {
        TransactionCursor cursor = new TransactionCursor(UUID.fromString("0196f8b2-9d1b-7634-8aa3-b95d685dbb4a"));
        String encodedCursor = TransactionCursorCodec.encode(cursor);
        Optional<TransactionCursor> decodedCursor = TransactionCursorCodec.decode(encodedCursor);
        assertThat(encodedCursor).isNotBlank();
        assertThat(decodedCursor).contains(cursor);
    }

    @Test
    @DisplayName("Should return empty when cursor is invalid")
    void invalidReturnsEmpty() {
        assertThat(TransactionCursorCodec.decode("not-base64!!!")).isEmpty();
    }
}
