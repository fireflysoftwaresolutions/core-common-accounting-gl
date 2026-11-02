/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.approval;

import io.fireflysoftware.core.accounting.models.entities.approval.GlFinancialApprovalControl;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Command identities are caller-retained; an unavailable completion never authorizes a replacement. */
public interface GlFinancialApprovalService {
    Mono<GlFinancialApprovalControl> request(String tenant, String book, UUID approvalId,
            UUID commandId, long businessUntilEpochMillis, byte[] completeOriginalPosting);
    Mono<GlFinancialApprovalControl> requestMapped(String tenant, String book, UUID approvalId,
            UUID commandId, long businessUntilEpochMillis, byte[] completeOriginalPosting,
            io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.MappingSnapshot proposal);
    Mono<GlFinancialApprovalControl> check(String tenant, String book, UUID approvalId,
            UUID commandId, long expectedRevision);
    Mono<GlFinancialApprovalControl> checkMapped(String tenant, String book, UUID approvalId,
            UUID commandId, long expectedRevision, UUID expectedSnapshotId, String expectedSnapshotSha256, String expectedIntentDigest);
    Mono<GlFinancialApprovalControl> revoke(String tenant, String book, UUID approvalId,
            UUID commandId, long expectedRevision);
    Mono<GlFinancialApprovalControl> current(String tenant, String book, UUID approvalId);
    Mono<io.fireflysoftware.core.accounting.intefaces.dtos.posting.approval.GlFinancialApprovalDetail> detail(
            String tenant, String book, UUID approvalId);
}
