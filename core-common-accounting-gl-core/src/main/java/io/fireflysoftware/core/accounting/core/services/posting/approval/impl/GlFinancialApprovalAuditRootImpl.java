/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.approval.impl;

import io.fireflysoftware.core.accounting.core.services.posting.approval.*;
import java.time.Clock;
import java.util.*;
import java.util.function.*;
import org.fireflyframework.core.audit.*;
import org.fireflyframework.core.audit.impl.SpringDataTransactionalAuditAppender;
import org.fireflyframework.core.transaction.*;
import org.fireflyframework.core.transaction.impl.DefaultBoundedRootTransaction;
import org.fireflyframework.kernel.audit.*;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;

/** The canonical audit append and every mapped approval effect use the same verified owner root. */
public final class GlFinancialApprovalAuditRootImpl {
    private final BoundedR2dbcResources resources;
    private final R2dbcEntityTemplate template;
    private final BoundedRootTransaction root;
    private final TransactionalAuditAppender append;
    private final GlFinancialApprovalAuthority authority;
    private final Clock clock;
    public GlFinancialApprovalAuditRootImpl(BoundedR2dbcResources resources,DatabaseClient client,
            R2dbcEntityTemplate template,R2dbcTransactionManager manager,CleanupPolicy cleanup,
            GlFinancialApprovalAuthority authority,Clock clock,LongSupplier nanos) {
        this.resources=BoundedR2dbcResources.verify(Objects.requireNonNull(resources).factory(),client,template,manager);
        this.template=template;this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);
        this.root=new DefaultBoundedRootTransaction(this.resources,Objects.requireNonNull(cleanup));
        this.append=new SpringDataTransactionalAuditAppender(this.resources,client,template,manager,authority,clock,nanos,UUID::randomUUID);
    }
    public void requireTemplate(R2dbcEntityTemplate expected){if(template!=expected)throw invalid();}
    public <T> Mono<T> execute(GlFinancialApprovalPermit permit,UUID commandId,String canonicalTransition,
            UUID predecessor,Function<AuditRecord,Mono<T>> writes) {return Mono.defer(()->{
        permit.budget().remaining();
        var event=new AuditEvent(commandId,new AuditStream(permit.tenant(),"core-common-accounting-gl","financial-approval",permit.approval()),
                permit.actor(),"accounting.gl.approval."+permit.action().name().toLowerCase(Locale.ROOT),AuditEvent.Outcome.COMPLETED,
                permit.approval(),commandId,predecessor,clock.instant(),
                io.fireflysoftware.core.accounting.core.services.posting.GlPostingRules.digest(canonicalTransition),permit.audit().policyRevision());
        var definition=new DefaultTransactionDefinition();definition.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return authority.revalidate(permit).timeout(permit.budget().remaining()).then(root.execute(permit.budget(),definition,scope->
            resources.factory().requireBoundScope(resources,scope).then(Mono.defer(()->authority.within(permit,event,()->append.append(scope,event))))
                .switchIfEmpty(Mono.error(invalid())).flatMap(provisional->{
                    var record=provisional.record();
                    if(provisional.replay() || record==null || !event.equals(record.event()) || !AuditRecordHasher.record(
                            new AuditCheckpoint(event.stream(),record.sequence()-1,record.previousHash()),event,record.recordedAt()).equals(record))return Mono.error(invalid());
                    permit.budget().remaining();scope.remaining();
                    return resources.factory().requireBoundScope(resources,scope).then(Mono.defer(()->writes.apply(record)))
                        .switchIfEmpty(Mono.error(invalid())).timeout(scope.remaining()).map(result->{permit.budget().remaining();scope.remaining();return result;});
                }).onErrorResume(error->resources.factory().markBoundScopeRollbackOnly(resources,scope)
                    .onErrorResume(markFailure->Mono.error(error)).then(Mono.error(error)))))
            .map(completed->{var facts=completed.transactionFacts();
                if(!facts.nativeAttempted() || !facts.commitAcknowledged() || !facts.boundaryCompleted() || facts.rollbackAcknowledged()
                        || completed.cleanupFact()!=CleanupFact.COMPLETE)throw GlFinancialApprovalRules.unavailable();
                permit.budget().remaining();return completed.value();
            });
    });}
    private static IllegalStateException invalid(){return new IllegalStateException("Paired immutable financial approval audit required");}
}
