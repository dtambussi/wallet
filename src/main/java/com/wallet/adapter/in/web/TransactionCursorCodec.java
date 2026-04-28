package com.wallet.adapter.in.web;

import com.wallet.domain.ledger.TransactionCursor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Encodes/decodes opaque pagination cursors as URL-safe Base64 of {@code entryId}. */
public final class TransactionCursorCodec {

    private TransactionCursorCodec() {}

    /**is i
     * Decodes a cursor token into its UUIDv7 ledger entry id.
     * e.g. "MDE5NmY4YjItOWQxYi03NjM0LThhYTMtYjk1ZDY4NWRiYjRh" -> "0196f8b2-9d1b-7634-8aa3-b95d685dbb4a".
     */
    public static Optional<TransactionCursor> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(raw);
            UUID entryId = UUID.fromString(new String(bytes, StandardCharsets.UTF_8));
            return Optional.of(new TransactionCursor(entryId));
        } catch (Exception parsingException) {
            return Optional.empty();
        }
    }

    /**
     * Encodes a UUIDv7 ledger entry id into a cursor token.
     * e.g. "0196f8b2-9d1b-7634-8aa3-b95d685dbb4a" -> "MDE5NmY4YjItOWQxYi03NjM0LThhYTMtYjk1ZDY4NWRiYjRh".
     */
    public static String encode(TransactionCursor cursor) {
        String payload = cursor.entryId().toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
