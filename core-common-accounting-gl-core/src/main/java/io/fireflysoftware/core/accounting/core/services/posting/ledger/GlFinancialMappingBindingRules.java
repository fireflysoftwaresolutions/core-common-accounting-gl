/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.ledger;

import io.fireflysoftware.core.accounting.core.services.posting.GlPostingIntentCodec;
import io.fireflysoftware.core.accounting.core.services.posting.approval.GlFinancialApprovalRules;
import io.fireflysoftware.core.accounting.models.entities.approval.*;

/** Verifies retained typed content and original root linkage; current four-eyes state is checked separately. */
public final class GlFinancialMappingBindingRules {
    private GlFinancialMappingBindingRules() {}
    public static GlLedgerMappingSnapshotRules.Validated retained(GlFinancialApprovalDefinition definition,GlFinancialApprovalMapping mapping) {
        if(mapping==null||!definition.id().equals(mapping.approvalId())||!definition.tenantId().equals(mapping.tenantId())
                ||!definition.bookId().equals(mapping.bookId())||!GlLedgerMappingSnapshotRules.FORMAT.equals(mapping.snapshotFormat())
                ||!definition.requestedEventId().equals(mapping.requestedEventId())||!definition.requestedRecordHash().equals(mapping.requestedRecordHash()))
            throw GlFinancialApprovalRules.refused();
        var decoded=GlLedgerMappingSnapshotRules.decode(mapping.snapshotJson());var reference=decoded.snapshot().reference();
        if(!decoded.canonical().equals(mapping.canonicalSnapshot())||!decoded.snapshotSha256().equals(mapping.snapshotSha256())
                ||!reference.id().equals(mapping.mappingId())||reference.version()!=mapping.mappingVersion()||!reference.digest().equals(mapping.definitionDigest()))
            throw GlFinancialApprovalRules.refused();
        GlLedgerMappingSnapshotRules.matches(definition.tenantId().toString(),definition.bookId().toString(),decoded,
                GlPostingIntentCodec.decode(definition.tenantId().toString(),definition.bookId().toString(),definition.originalJson()).validated());
        return decoded;
    }
    public static String canonical(GlFinancialApprovalMapping mapping) {
        return GlLedgerMappingSnapshotRules.binding(mapping.approvalId(),mapping.snapshotId(),GlLedgerMappingSnapshotRules.decode(mapping.snapshotJson()));
    }
}
