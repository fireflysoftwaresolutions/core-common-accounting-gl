/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.models.repositories.approval;

import io.fireflysoftware.core.accounting.models.entities.approval.*;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

public final class GlFinancialApprovalRepositories {
    private GlFinancialApprovalRepositories() {}
    public interface Definitions extends ReactiveCrudRepository<GlFinancialApprovalDefinition, UUID> {
        Mono<GlFinancialApprovalDefinition> findByTenantIdAndBookIdAndId(UUID tenantId, UUID bookId, UUID id);
        Mono<GlFinancialApprovalDefinition> findByTenantIdAndBookIdAndPostingActionIdAndMakerIssuerAndMakerSubject(
                UUID tenantId, UUID bookId, UUID postingActionId, String makerIssuer, String makerSubject);
    }
    public interface Controls extends ReactiveCrudRepository<GlFinancialApprovalControl, UUID> {
        Mono<GlFinancialApprovalControl> findByTenantIdAndBookIdAndApprovalId(UUID tenantId, UUID bookId, UUID approvalId);
    }
    public interface Transitions extends ReactiveCrudRepository<GlFinancialApprovalTransition, UUID> {
        Mono<GlFinancialApprovalTransition> findByTenantIdAndBookIdAndEventId(UUID tenantId, UUID bookId, UUID eventId);
    }
    public interface Integrity extends ReactiveCrudRepository<GlFinancialApprovalControl, UUID> {
        @Query("SELECT public.gl_financial_approval_verify(:tenant,:book,:approval)")
        Mono<Boolean> verify(@Param("tenant") UUID tenant, @Param("book") UUID book, @Param("approval") UUID approval);
        @Query("SELECT public.gl_financial_approval_mapping_current(:tenant,:book,:approval)")
        Mono<Boolean> currentMapping(@Param("tenant") UUID tenant, @Param("book") UUID book, @Param("approval") UUID approval);
    }
    public interface Mappings extends ReactiveCrudRepository<GlFinancialApprovalMapping, UUID> {
        Mono<GlFinancialApprovalMapping> findByTenantIdAndBookIdAndApprovalId(UUID tenantId,UUID bookId,UUID approvalId);
    }
}
