package com.wallet.application.command.settlement;

import com.wallet.application.audit.WalletCommandAudit;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.result.SettleResult;
import com.wallet.application.service.UserQueryService;
import com.wallet.domain.audit.AuditContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementCommandHandler {

    private final LedgerRepository ledgerRepository;
    private final UserQueryService userQueryService;
    private final WalletCommandAudit commandAudit;

    public SettlementCommandHandler(
        LedgerRepository ledgerRepository,
        UserQueryService userQueryService,
        WalletCommandAudit commandAudit
    ) {
        this.ledgerRepository = ledgerRepository;
        this.userQueryService = userQueryService;
        this.commandAudit = commandAudit;
    }

    @Transactional
    public SettleResult settle(UUID userId, AuditContext audit) {
        if (!userQueryService.existsById(userId)) {
            SettleResult result = new SettleResult.UserNotFound("User not found: " + userId);
            commandAudit.recordSettlementOutcome(result, audit);
            return result;
        }

        Map<String, BigDecimal> settled = ledgerRepository.settleAllPending(userId);

        if (settled.isEmpty()) {
            SettleResult result = new SettleResult.NothingToSettle("No pending balance to settle");
            commandAudit.recordSettlementOutcome(result, audit);
            return result;
        }

        SettleResult successResult = new SettleResult.Success(settled);
        commandAudit.recordSettlementOutcome(successResult, audit);
        return successResult;
    }
}
