/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.intefaces.dtos.posting.approval;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record GlFinancialApprovalTransitionRequest(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID commandId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedRevision) {}
