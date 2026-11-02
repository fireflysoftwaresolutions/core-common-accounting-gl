/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.ledger;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Current Core-owned mapping observation; a submitted proposal or client dictionary is never approval. */
public interface GlApprovedLedgerMappingPort {
    record Approved(UUID approvalId, UUID snapshotId, GlLedgerMappingSnapshotRules.Validated snapshot,
            String approvalProofDigest, long validUntilEpochMillis) {}
    Mono<Approved> current(GlAccountingAuthority.Admission admission, GlPostingRules.Validated posting);
}
