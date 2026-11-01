/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.approval;

import io.fireflysoftware.core.accounting.core.services.posting.GlPostingRules;
import java.util.UUID;
import java.util.function.Supplier;
import org.fireflyframework.core.audit.AuditAppendAuthority;
import org.fireflyframework.kernel.audit.AuditEvent;
import reactor.core.publisher.Mono;

public interface GlFinancialApprovalAuthority extends AuditAppendAuthority {
    enum Action { REQUEST, CHECK, REVOKE, READ, CONSUME }
    Mono<GlFinancialApprovalPermit> authorize(Action action, UUID tenant, UUID book, UUID approval,
            GlPostingRules.Validated intent, long businessUntilEpochMillis,
            org.fireflyframework.core.transaction.R2dbcOperationBudget original);
    Mono<GlFinancialApprovalPermit> authorizeMapped(Action action, UUID tenant, UUID book, UUID approval,
            GlPostingRules.Validated intent, long businessUntilEpochMillis,
            io.fireflysoftware.core.accounting.core.services.posting.ledger.GlLedgerMappingSnapshotRules.Validated mapping,
            org.fireflyframework.core.transaction.R2dbcOperationBudget original);
    Mono<Void> revalidate(GlFinancialApprovalPermit original);
    <T> Mono<T> within(GlFinancialApprovalPermit original, AuditEvent expected, Supplier<Mono<T>> append);
}
