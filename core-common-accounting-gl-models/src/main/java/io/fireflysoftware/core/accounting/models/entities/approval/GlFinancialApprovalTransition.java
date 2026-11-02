/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.models.entities.approval;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Append-only command result paired with the original canonical audit event. */
@Table(schema = "public", value = "gl_financial_approval_transition")
public record GlFinancialApprovalTransition(@Id UUID eventId, UUID tenantId, UUID bookId,
        UUID approvalId, String operation, long revision, String state,
        String actorIssuer, String actorSubject, String actorStaffRealm, UUID actorStaffId,
        String canonicalTransition, String transitionDigest, UUID predecessorEventId,
        String eventRecordHash, Instant recordedAt, String authorizationPolicyRevision,
        String authorizationDecisionId, String auditPolicyRevision, String auditDecisionId,
        String accountDecisionDigest) {}
