package com.wallet.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;

public record LedgerEntryResponse(
    @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7", description = "Identifier of the ledger entry (UUID)")
    String ledgerEntryId
) {}
