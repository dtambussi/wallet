package com.wallet.domain.audit;

import java.util.Map;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * One append-only row in {@code financial_audit_events}: a command was evaluated and an outcome was reached.
 * Details must stay free of high-risk PII; amounts are kept as plain strings for exact decimal traceability.
 */
public record FinancialAuditEvent(
    String correlationId,
    UUID subjectUserId,
    String commandType,
    String outcome,
    @Nullable UUID ledgerEntryId,
    @Nullable String idempotencyKey,
    Map<String, Object> details
) {
    public FinancialAuditEvent {
        details = Map.copyOf(details);
    }
}
