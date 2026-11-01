/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.models.entities.approval;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Immutable maker-owned complete economic and source intent; no bearer credential is stored. */
@Table(schema = "public", value = "gl_financial_approval_definition")
public record GlFinancialApprovalDefinition(@Id UUID id, UUID tenantId, UUID bookId,
        UUID postingActionId, String originalJson, String canonicalIntent, String intentDigest,
        String canonicalEconomicIntent, String economicIntentDigest, String sourceClaimKey,
        String makerIssuer, String makerSubject, String makerClientId, String makerPlatformTenant,
        String makerStaffRealm, UUID makerStaffId, long businessUntilEpochMillis,
        UUID requestedEventId, String requestedRecordHash) {}
