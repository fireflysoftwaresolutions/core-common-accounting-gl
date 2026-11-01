/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.intefaces.dtos.posting.approval;

import io.fireflysoftware.core.accounting.intefaces.dtos.posting.GlPostingRequest;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.MappingSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Authorized retained review material; a read does not grant approval or execution authority. */
@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record GlFinancialApprovalDetail(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) GlFinancialApprovalReceipt approval,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long businessUntilEpochMillis,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) GlPostingRequest originalPosting,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String originalPostingJson,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String intentDigest,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID snapshotId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String snapshotFormat,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) MappingSnapshot mappingSnapshot,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String snapshotSha256) {}
