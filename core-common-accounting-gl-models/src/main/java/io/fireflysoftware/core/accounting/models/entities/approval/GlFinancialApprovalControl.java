/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.models.entities.approval;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Only the explicit approval lifecycle advances; the financial intent never changes. */
@Table(schema = "public", value = "gl_financial_approval_control")
public record GlFinancialApprovalControl(@Id UUID approvalId, UUID tenantId, UUID bookId,
        String state, long revision, Long approvedUntilEpochMillis, String checkerIssuer,
        String checkerSubject, String checkerStaffRealm, UUID checkerStaffId,
        UUID lastEventId, String lastRecordHash, String transitionDigest) {}
