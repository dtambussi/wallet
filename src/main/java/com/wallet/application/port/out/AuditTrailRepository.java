package com.wallet.application.port.out;

import com.wallet.domain.audit.FinancialAuditEvent;

/**
 * Persists an append-only financial command audit line. Implementations only INSERT; no updates or deletes.
 */
public interface AuditTrailRepository {
    void append(FinancialAuditEvent event);
}
