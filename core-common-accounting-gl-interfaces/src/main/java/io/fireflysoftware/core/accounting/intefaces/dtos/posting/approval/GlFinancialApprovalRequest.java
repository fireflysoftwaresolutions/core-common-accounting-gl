/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.intefaces.dtos.posting.approval;

import io.fireflysoftware.core.accounting.intefaces.dtos.posting.GlPostingRequest;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.MappingSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record GlFinancialApprovalRequest(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID commandId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long businessUntilEpochMillis,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) GlPostingRequest originalPosting,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) MappingSnapshot mappingSnapshot) {}
