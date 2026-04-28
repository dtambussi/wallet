package com.wallet.domain.ledger;

public final class LedgerEntryTypes {

    private LedgerEntryTypes() {}

    public static final String DEPOSIT = "DEPOSIT";
    public static final String WITHDRAWAL = "WITHDRAWAL";
    public static final String TRANSFER = "TRANSFER";
    public static final String FX_EXCHANGE = "FX_EXCHANGE";
    public static final String BATCH_TRANSFER = "BATCH_TRANSFER";
    public static final String CROSS_CURRENCY_TRANSFER = "CROSS_CURRENCY_TRANSFER";
    public static final String WITHDRAWAL_REVERSAL = "WITHDRAWAL_REVERSAL";
}
