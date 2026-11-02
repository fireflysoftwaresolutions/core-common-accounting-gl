/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.models.repositories.approval;

import io.fireflysoftware.core.accounting.models.entities.approval.*;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface GlFinancialApprovalStore {
    Mono<GlFinancialApprovalDefinition> definition(UUID tenant, UUID book, UUID approval);
    Mono<GlFinancialApprovalDefinition> definitionForPosting(UUID tenant, UUID book, UUID postingAction, String makerIssuer, String makerSubject);
    Mono<GlFinancialApprovalControl> control(UUID tenant, UUID book, UUID approval);
    Mono<GlFinancialApprovalTransition> transition(UUID tenant, UUID book, UUID command);
    Mono<GlFinancialApprovalMapping> mapping(UUID tenant, UUID book, UUID approval);
    Mono<Void> insertMapping(GlFinancialApprovalMapping mapping);
    Mono<Void> requireCurrentMapping(UUID tenant, UUID book, UUID approval);
    Mono<Void> insertDefinition(GlFinancialApprovalDefinition definition);
    Mono<Void> insertControl(GlFinancialApprovalControl control);
    Mono<Void> insertTransition(GlFinancialApprovalTransition transition);
    Mono<Void> advance(GlFinancialApprovalControl before, GlFinancialApprovalControl after);
    Mono<Void> requireIntegrity(UUID tenant, UUID book, UUID approval);
}
