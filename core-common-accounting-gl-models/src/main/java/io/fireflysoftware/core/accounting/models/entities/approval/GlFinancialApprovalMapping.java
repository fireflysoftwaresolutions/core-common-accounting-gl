/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.models.entities.approval;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Immutable proposal paired with its original owner request; approval is separate current owner state. */
@Table(schema = "public", value = "gl_financial_approval_mapping")
public record GlFinancialApprovalMapping(@Id UUID approvalId, UUID tenantId, UUID bookId, UUID snapshotId,
        String snapshotFormat, UUID mappingId, long mappingVersion, String definitionDigest,
        String snapshotJson, String canonicalSnapshot, String snapshotSha256,
        UUID requestedEventId, String requestedRecordHash) {}
