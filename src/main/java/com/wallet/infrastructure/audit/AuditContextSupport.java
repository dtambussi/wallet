package com.wallet.infrastructure.audit;

import com.wallet.domain.audit.AuditContext;
import com.wallet.infrastructure.web.RequestIdMdcFilter;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Binds a wallet command to the current HTTP request id (or a stable substitute when the filter did not set MDC).
 * MDC means "Mapped Diagnostic Context": per-request log context stored on the current thread.
 */
public final class AuditContextSupport {

    private AuditContextSupport() {}

    public static AuditContext forPathUser(UUID pathUserId) {
        String correlationId = Optional.ofNullable(MDC.get(RequestIdMdcFilter.MDC_KEY))
            .filter(s -> !s.isBlank())
            .orElse("unassigned");
        return new AuditContext(correlationId, pathUserId);
    }
}
