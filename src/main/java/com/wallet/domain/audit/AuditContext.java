package com.wallet.domain.audit;

import java.util.UUID;

/**
 * Ties a mutating command to a request: {@code correlationId} (typically from {@code X-Request-Id})
 * and the wallet user id in the path (intentionally not a logged-in "actor" when authentication is not deployed).
 */
public record AuditContext(String correlationId, UUID subjectUserId) {}
