/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.approval.impl;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.core.services.posting.approval.GlFinancialApprovalRules;
import io.fireflysoftware.core.accounting.models.entities.approval.*;
import io.fireflysoftware.core.accounting.models.repositories.approval.GlFinancialApprovalStore;
import java.time.Clock;
import java.util.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import reactor.core.publisher.Mono;

/** Protected metadata read before full-intent command admission, under the same original command limit. */
public final class GlFinancialApprovalLookupImpl {
    public record Snapshot(GlFinancialApprovalDefinition definition,GlFinancialApprovalControl control,
            Optional<GlFinancialApprovalTransition> command,Optional<GlFinancialApprovalMapping> mapping) {}
    private final GlAccountingAuthority authority;
    private final GlAccountingTransaction transaction;
    private final GlFinancialApprovalStore store;
    private final Clock clock;
    public GlFinancialApprovalLookupImpl(GlAccountingAuthority authority,GlAccountingTransaction transaction,
            GlFinancialApprovalStore store,Clock clock) {
        this.authority=Objects.requireNonNull(authority);this.transaction=Objects.requireNonNull(transaction);
        this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);
    }
    public Mono<Optional<Snapshot>> find(UUID tenant,UUID book,UUID approval,UUID command,R2dbcOperationBudget original){
        return read(tenant,book,approval,command,original,false,()->store.definition(tenant,book,approval));
    }
    public Mono<Optional<Snapshot>> forPosting(UUID tenant,UUID book,UUID action,String issuer,String subject,R2dbcOperationBudget original){
        return read(tenant,book,action,null,original,true,()->store.definitionForPosting(tenant,book,action,issuer,subject));
    }
    private Mono<Optional<Snapshot>> read(UUID tenant,UUID book,UUID resource,UUID command,R2dbcOperationBudget original,boolean currentMapping,
            java.util.function.Supplier<Mono<GlFinancialApprovalDefinition>> selection){return Mono.defer(()->{
        original.remaining();String canonical=GlPostingRules.canonical("GL_READ_CONTEXT_V1",List.of(tenant.toString(),book.toString(),"read",resource.toString()));
        var intent=new AccountingApprovalIntent(tenant.toString(),book.toString(),"read",GlPostingRules.digest(canonical),canonical,Map.of("approvalLookup",resource.toString()));
        return authority.authorize(intent,false).timeout(original.remaining()).flatMap(admitted->{
            var observed=clock.instant();long until=Math.min(admitted.validUntilEpochMillis(),observed.plus(original.remaining()).toEpochMilli());
            var bounded=new GlAccountingAuthority.Admission(admitted.actor(),admitted.approval(),until,admitted.intent(),admitted.accountIds());
            return transaction.executeWitnessed(bounded,operation->Mono.defer(selection).flatMap(definition->
                    store.control(tenant,book,definition.id()).switchIfEmpty(Mono.error(GlFinancialApprovalRules.refused())).flatMap(control->{
                        Mono<Optional<GlFinancialApprovalTransition>> retained=command==null?Mono.just(Optional.empty()):
                            store.transition(tenant,book,command).map(Optional::of).defaultIfEmpty(Optional.empty());
                        return retained.flatMap(retainedCommand->store.requireIntegrity(tenant,book,definition.id())
                            .then(store.mapping(tenant,book,definition.id()).map(Optional::of).defaultIfEmpty(Optional.empty()))
                            .flatMap(mapping->(currentMapping&&mapping.isPresent()?store.requireCurrentMapping(tenant,book,definition.id()):Mono.<Void>empty())
                                .thenReturn(Optional.of(new Snapshot(definition,control,retainedCommand,mapping)))));
                    })).defaultIfEmpty(Optional.empty())).map(completed->{original.remaining();completed.operation().requireCurrent();return completed.value();});
        });
    });}
}
