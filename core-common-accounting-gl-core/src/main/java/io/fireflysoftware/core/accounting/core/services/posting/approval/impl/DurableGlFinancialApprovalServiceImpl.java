/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.approval.impl;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.core.services.posting.approval.*;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.*;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.MappingSnapshot;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.approval.*;
import io.fireflysoftware.core.accounting.models.entities.approval.*;
import io.fireflysoftware.core.accounting.models.repositories.approval.*;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.kernel.audit.*;
import reactor.core.publisher.Mono;

/** Maker/checker commands retain one immutable economic intent and original command identity. */
public final class DurableGlFinancialApprovalServiceImpl implements GlFinancialApprovalService {
    private final GlFinancialApprovalAuthority authority;
    private final GlFinancialApprovalAuditRootImpl root;
    private final GlFinancialApprovalLookupImpl lookup;
    private final GlFinancialApprovalStore store;
    private final Clock clock;
    private final LongSupplier nanos;
    public DurableGlFinancialApprovalServiceImpl(GlFinancialApprovalAuthority authority,GlFinancialApprovalAuditRootImpl root,
            GlFinancialApprovalLookupImpl lookup,GlFinancialApprovalStore store,Clock clock,LongSupplier nanos) {
        this.authority=Objects.requireNonNull(authority);this.root=Objects.requireNonNull(root);this.lookup=Objects.requireNonNull(lookup);
        this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
    }
    @Override public Mono<GlFinancialApprovalControl> request(String tenant,String book,UUID approval,UUID command,long businessUntil,byte[] original){
        return requestInternal(tenant,book,approval,command,businessUntil,original,null);
    }
    @Override public Mono<GlFinancialApprovalControl> requestMapped(String tenant,String book,UUID approval,UUID command,long businessUntil,
            byte[] original,MappingSnapshot proposal){
        if(original==null||original.length==0||original.length>1048576)return Mono.error(GlPostingRules.bad());
        byte[] captured=original.clone();
        return Mono.defer(()->requestInternal(tenant,book,approval,command,businessUntil,captured,GlLedgerMappingSnapshotRules.validate(proposal)));
    }
    private Mono<GlFinancialApprovalControl> requestInternal(String tenant,String book,UUID approval,UUID command,long businessUntil,
            byte[] original,GlLedgerMappingSnapshotRules.Validated mapping){
        if(original==null || original.length==0 || original.length>1048576)return Mono.error(GlPostingRules.bad());
        byte[] captured=original.clone();return Mono.defer(()->{
            var budget=begin();var scope=scope(tenant,book,approval,command);var decoded=GlPostingIntentCodec.decode(tenant,book,captured);
            if(mapping!=null)GlLedgerMappingSnapshotRules.matches(tenant,book,mapping,decoded.validated());
            return lookup.find(scope[0],scope[1],approval,command,budget).flatMap(retained->
                authority.authorizeMapped(GlFinancialApprovalAuthority.Action.REQUEST,scope[0],scope[1],approval,decoded.validated(),businessUntil,mapping,budget).flatMap(permit->{
                    if(retained.isPresent()) {
                        var snapshot=retained.orElseThrow();GlFinancialApprovalRules.maker(snapshot.definition(),permit.actor());
                        if(!snapshot.definition().originalJson().equals(decoded.originalJson()) || !snapshot.definition().intentDigest().equals(decoded.validated().intentDigest())
                                || snapshot.definition().businessUntilEpochMillis()!=businessUntil)throw GlFinancialApprovalRules.refused();
                        if(snapshot.mapping().isPresent()!=(mapping!=null)||mapping!=null
                                &&!GlFinancialMappingBindingRules.retained(snapshot.definition(),snapshot.mapping().orElseThrow()).equals(mapping))
                            throw GlFinancialApprovalRules.refused();
                        return replay(snapshot,command,"request",permit);
                    }
                    var actor=permit.actor();var intent=decoded.validated();
                    var pending=new GlFinancialApprovalDefinition(approval,scope[0],scope[1],UUID.fromString(intent.request().actionId()),decoded.originalJson(),
                        intent.canonicalIntent(),intent.intentDigest(),intent.canonicalEconomicIntent(),intent.economicIntentDigest(),intent.sourceClaimKey(),
                        actor.issuer(),actor.subject(),actor.clientId(),actor.platformTenantId(),actor.staffRealm(),actor.staffId(),businessUntil,command,"");
                    var reference=mapping==null?null:mapping.snapshot().reference();
                    var pendingMapping=mapping==null?null:new GlFinancialApprovalMapping(approval,scope[0],scope[1],UUID.randomUUID(),GlLedgerMappingSnapshotRules.FORMAT,
                            reference.id(),reference.version(),reference.digest(),mapping.originalJson(),mapping.canonical(),mapping.snapshotSha256(),command,"");
                    String canonical=canonical(pending,"request",null,"REQUESTED",permit,businessUntil,pendingMapping);
                    return root.execute(permit,command,canonical,null,record->{
                        var definition=new GlFinancialApprovalDefinition(pending.id(),pending.tenantId(),pending.bookId(),pending.postingActionId(),pending.originalJson(),
                            pending.canonicalIntent(),pending.intentDigest(),pending.canonicalEconomicIntent(),pending.economicIntentDigest(),pending.sourceClaimKey(),
                            pending.makerIssuer(),pending.makerSubject(),pending.makerClientId(),pending.makerPlatformTenant(),pending.makerStaffRealm(),pending.makerStaffId(),
                            pending.businessUntilEpochMillis(),command,record.recordHash());
                        var control=new GlFinancialApprovalControl(approval,scope[0],scope[1],"REQUESTED",0,null,null,null,null,null,command,record.recordHash(),GlPostingRules.digest(canonical));
                        var binding=pendingMapping==null?null:new GlFinancialApprovalMapping(approval,scope[0],scope[1],pendingMapping.snapshotId(),pendingMapping.snapshotFormat(),
                            pendingMapping.mappingId(),pendingMapping.mappingVersion(),pendingMapping.definitionDigest(),pendingMapping.snapshotJson(),pendingMapping.canonicalSnapshot(),
                            pendingMapping.snapshotSha256(),command,record.recordHash());
                        return store.insertDefinition(definition).then(binding==null?Mono.empty():store.insertMapping(binding))
                            .then(store.insertTransition(transition(definition,"request",control,canonical,null,permit,record)))
                            .then(store.insertControl(control)).then(binding==null?Mono.empty():store.requireCurrentMapping(scope[0],scope[1],approval))
                            .then(verify(definition,control));
                    });
                }));
        });
    }
    @Override public Mono<GlFinancialApprovalControl> check(String tenant,String book,UUID approval,UUID command,long expected){
        return change(tenant,book,approval,command,expected,true,null);
    }
    private record ExpectedReview(UUID snapshotId,String snapshotSha256,String intentDigest) {}
    @Override public Mono<GlFinancialApprovalControl> checkMapped(String tenant,String book,UUID approval,UUID command,long expected,
            UUID expectedSnapshotId,String expectedSnapshotSha256,String expectedIntentDigest){
        return Mono.defer(()->{
            if(expectedSnapshotId==null)throw GlPostingRules.bad();
            GlPostingRules.uuid(expectedSnapshotId.toString());GlPostingRules.digestField(expectedSnapshotSha256);GlPostingRules.digestField(expectedIntentDigest);
            return change(tenant,book,approval,command,expected,true,new ExpectedReview(expectedSnapshotId,expectedSnapshotSha256,expectedIntentDigest));
        });
    }
    @Override public Mono<GlFinancialApprovalControl> revoke(String tenant,String book,UUID approval,UUID command,long expected){
        return change(tenant,book,approval,command,expected,false,null);
    }
    private Mono<GlFinancialApprovalControl> change(String tenant,String book,UUID approval,UUID command,long expected,boolean checking,ExpectedReview review){return Mono.defer(()->{
        var budget=begin();var scope=scope(tenant,book,approval,command);
        return lookup.find(scope[0],scope[1],approval,command,budget).filter(Optional::isPresent).switchIfEmpty(Mono.error(GlFinancialApprovalRules.refused()))
            .flatMap(optional->{var snapshot=optional.orElseThrow();var definition=snapshot.definition();var before=snapshot.control();
                var decoded=GlPostingIntentCodec.decode(tenant,book,definition.originalJson());requireIntent(definition,decoded.validated());
                var action=checking?GlFinancialApprovalAuthority.Action.CHECK:GlFinancialApprovalAuthority.Action.REVOKE;
                var mapping=snapshot.mapping().map(row->GlFinancialMappingBindingRules.retained(definition,row)).orElse(null);
                if(checking&&mapping!=null&&review==null)throw GlFinancialApprovalRules.refused();
                if(review!=null&&(mapping==null||!snapshot.mapping().orElseThrow().snapshotId().equals(review.snapshotId())
                        ||!mapping.snapshotSha256().equals(review.snapshotSha256())||!definition.intentDigest().equals(review.intentDigest())))
                    throw GlFinancialApprovalRules.refused();
                return authority.authorizeMapped(action,scope[0],scope[1],approval,decoded.validated(),definition.businessUntilEpochMillis(),mapping,budget).flatMap(permit->
                    Mono.justOrEmpty(snapshot.command()).flatMap(previous->replay(snapshot,command,checking?"check":"revoke",permit))
                    .switchIfEmpty(Mono.defer(()->{
                        if(expected!=before.revision() || "REVOKED".equals(before.state()))throw GlFinancialApprovalRules.refused();
                        if(checking){
                            GlFinancialApprovalRules.current(definition,before,clock.instant());GlFinancialApprovalRules.checker(definition,permit.actor());
                            if(!"REQUESTED".equals(before.state()) || before.revision()!=0)throw GlFinancialApprovalRules.refused();
                        }else{
                            boolean maker=definition.makerIssuer().equals(permit.actor().issuer())&&definition.makerSubject().equals(permit.actor().subject());
                            boolean checker=Objects.equals(before.checkerIssuer(),permit.actor().issuer())&&Objects.equals(before.checkerSubject(),permit.actor().subject())
                                && Objects.equals(before.checkerStaffRealm(),permit.actor().staffRealm())&&Objects.equals(before.checkerStaffId(),permit.actor().staffId());
                            if(maker)GlFinancialApprovalRules.maker(definition,permit.actor());else if(!checker)throw GlFinancialApprovalRules.refused();
                        }
                        String state=checking?"APPROVED":"REVOKED",operation=checking?"check":"revoke";
                        String canonical=canonical(definition,operation,before,state,permit,checking?definition.businessUntilEpochMillis():0,snapshot.mapping().orElse(null));
                        return root.execute(permit,command,canonical,before.lastEventId(),record->{
                            var actor=permit.actor();var after=new GlFinancialApprovalControl(approval,scope[0],scope[1],state,Math.addExact(before.revision(),1),
                                checking?Long.valueOf(definition.businessUntilEpochMillis()):before.approvedUntilEpochMillis(),
                                checking?actor.issuer():before.checkerIssuer(),checking?actor.subject():before.checkerSubject(),
                                checking?actor.staffRealm():before.checkerStaffRealm(),checking?actor.staffId():before.checkerStaffId(),
                                command,record.recordHash(),GlPostingRules.digest(canonical));
                            return store.insertTransition(transition(definition,operation,after,canonical,before.lastEventId(),permit,record))
                                .then(store.advance(before,after)).then(checking&&mapping!=null?store.requireCurrentMapping(scope[0],scope[1],approval):Mono.empty())
                                .then(verify(definition,after));
                        });
                    })));
            });
    });}
    @Override public Mono<GlFinancialApprovalControl> current(String tenant,String book,UUID approval){return Mono.defer(()->{
        var budget=begin();var scope=scope(tenant,book,approval,approval);
        return lookup.find(scope[0],scope[1],approval,approval,budget).filter(Optional::isPresent).switchIfEmpty(Mono.error(GlFinancialApprovalRules.refused()))
            .flatMap(optional->{var snapshot=optional.orElseThrow();var definition=snapshot.definition();
                var decoded=GlPostingIntentCodec.decode(tenant,book,definition.originalJson());requireIntent(definition,decoded.validated());
                var mapping=snapshot.mapping().map(row->GlFinancialMappingBindingRules.retained(definition,row)).orElse(null);
                return authority.authorizeMapped(GlFinancialApprovalAuthority.Action.READ,scope[0],scope[1],approval,decoded.validated(),definition.businessUntilEpochMillis(),mapping,budget)
                    .flatMap(permit->authority.revalidate(permit).thenReturn(snapshot.control())).doOnNext(ignored->budget.remaining());
            });
    });}
    @Override public Mono<GlFinancialApprovalDetail> detail(String tenant,String book,UUID approval){return Mono.defer(()->{
        var budget=begin();var scope=scope(tenant,book,approval,approval);
        return lookup.find(scope[0],scope[1],approval,approval,budget).filter(Optional::isPresent).switchIfEmpty(Mono.error(GlFinancialApprovalRules.refused()))
            .flatMap(optional->{var snapshot=optional.orElseThrow();var definition=snapshot.definition();var control=snapshot.control();
                var binding=snapshot.mapping().orElseThrow(GlFinancialApprovalRules::refused);
                var mapping=GlFinancialMappingBindingRules.retained(definition,binding);
                var decoded=GlPostingIntentCodec.decode(tenant,book,definition.originalJson());requireIntent(definition,decoded.validated());
                return authority.authorizeMapped(GlFinancialApprovalAuthority.Action.READ,scope[0],scope[1],approval,decoded.validated(),definition.businessUntilEpochMillis(),mapping,budget)
                    .flatMap(permit->authority.revalidate(permit).then(Mono.fromSupplier(()->{budget.remaining();
                        return new GlFinancialApprovalDetail(new GlFinancialApprovalReceipt(control.approvalId(),control.state(),control.revision(),
                                control.approvedUntilEpochMillis(),control.lastEventId()),definition.businessUntilEpochMillis(),decoded.validated().request(),
                                definition.originalJson(),definition.intentDigest(),binding.snapshotId(),binding.snapshotFormat(),mapping.snapshot(),mapping.snapshotSha256());
                    })));
            });
    });}
    private Mono<GlFinancialApprovalControl> replay(GlFinancialApprovalLookupImpl.Snapshot snapshot,UUID command,String operation,GlFinancialApprovalPermit permit){
        var definition=snapshot.definition();return Mono.justOrEmpty(snapshot.command())
            .filter(row->row.eventId().equals(command))
            .filter(row->row.approvalId().equals(definition.id())&&row.operation().equals(operation)&&row.actorIssuer().equals(permit.actor().issuer())
                &&row.actorSubject().equals(permit.actor().subject())&&row.actorStaffRealm().equals(permit.actor().staffRealm())&&row.actorStaffId().equals(permit.actor().staffId()))
            .switchIfEmpty(Mono.error(GlFinancialApprovalRules.refused())).then(authority.revalidate(permit)).thenReturn(snapshot.control());
    }
    private Mono<GlFinancialApprovalControl> verify(GlFinancialApprovalDefinition definition,GlFinancialApprovalControl control){
        return store.requireIntegrity(definition.tenantId(),definition.bookId(),definition.id())
            .then(store.definition(definition.tenantId(),definition.bookId(),definition.id())).filter(definition::equals)
            .switchIfEmpty(Mono.error(GlFinancialApprovalRules.unavailable())).then(store.control(control.tenantId(),control.bookId(),control.approvalId()))
            .filter(control::equals).switchIfEmpty(Mono.error(GlFinancialApprovalRules.unavailable()));
    }
    private static String canonical(GlFinancialApprovalDefinition definition,String operation,GlFinancialApprovalControl before,String state,
            GlFinancialApprovalPermit permit,long until,GlFinancialApprovalMapping mapping){
        return GlFinancialApprovalRules.transition(definition,operation,before,state,permit.actor(),until,
                permit.authorization().policyRevision(),permit.authorization().decisionId(),permit.audit().policyRevision(),permit.audit().decisionId())
            +GlPostingRules.canonical("GL_APPROVAL_ACCOUNT_PROVENANCE_V1",List.of(permit.accountDecisionDigest()))
            +(mapping==null?"":GlFinancialMappingBindingRules.canonical(mapping));
    }
    private static GlFinancialApprovalTransition transition(GlFinancialApprovalDefinition definition,String operation,GlFinancialApprovalControl control,
            String canonical,UUID previous,GlFinancialApprovalPermit permit,AuditRecord record){
        var actor=permit.actor();return new GlFinancialApprovalTransition(record.event().eventId(),definition.tenantId(),definition.bookId(),definition.id(),operation,
            control.revision(),control.state(),actor.issuer(),actor.subject(),actor.staffRealm(),actor.staffId(),canonical,GlPostingRules.digest(canonical),previous,
            record.recordHash(),record.recordedAt(),permit.authorization().policyRevision(),permit.authorization().decisionId(),permit.audit().policyRevision(),permit.audit().decisionId(),permit.accountDecisionDigest());
    }
    static void requireIntent(GlFinancialApprovalDefinition definition,GlPostingRules.Validated intent){
        if(!definition.canonicalIntent().equals(intent.canonicalIntent())||!definition.intentDigest().equals(intent.intentDigest())
            ||!definition.canonicalEconomicIntent().equals(intent.canonicalEconomicIntent())||!definition.economicIntentDigest().equals(intent.economicIntentDigest())
            ||!definition.sourceClaimKey().equals(intent.sourceClaimKey())||!definition.postingActionId().toString().equals(intent.request().actionId()))throw GlFinancialApprovalRules.refused();
    }
    private R2dbcOperationBudget begin(){var observed=clock.instant();long before=nanos.getAsLong();return R2dbcOperationBudget.observed(observed,observed.plusSeconds(5),before,Duration.ofSeconds(5),clock,nanos);}
    private static UUID[] scope(String tenant,String book,UUID approval,UUID command){GlPostingRules.uuid(tenant);GlPostingRules.uuid(book);
        GlPostingRules.uuid(Objects.requireNonNull(approval).toString());GlPostingRules.uuid(Objects.requireNonNull(command).toString());return new UUID[]{UUID.fromString(tenant),UUID.fromString(book)};}
}
