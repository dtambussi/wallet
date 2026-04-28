package com.wallet.domain.audit;

public final class AuditCommandTypes {

    private AuditCommandTypes() {}

    public static final String DEPOSIT = "DEPOSIT";
    public static final String WITHDRAWAL = "WITHDRAWAL";
    public static final String TRANSFER = "TRANSFER";
    public static final String FX_QUOTE = "FX_QUOTE";
    public static final String FX_EXCHANGE = "FX_EXCHANGE";
    public static final String BATCH_TRANSFER = "BATCH_TRANSFER";
    public static final String SETTLEMENT = "SETTLEMENT";
    public static final String PAYOUT_WORKER = "PAYOUT_WORKER";
}
