/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.approval.impl;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.core.services.posting.approval.*;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.GlLedgerMappingSnapshotRules;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.kernel.audit.*;
import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import reactor.core.publisher.*;

/** Same-current signed staff identity and actual engine provenance, never PDP permission as approval. */
public final class CurrentGlFinancialApprovalAuthorityImpl implements GlFinancialApprovalAuthority {
    public record Profile(String identityIssuer, String approvalIssuer, String clientClaim, String canonicalStaffRealm,
            Duration maximumBusinessLifetime) {
        public Profile {
            for(String value:List.of(identityIssuer,approvalIssuer)) {
                var uri=URI.create(value);
                if(!Set.of("https","http").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)
                    throw new IllegalArgumentException("Explicit trusted approval issuer required");
            }
            GlPostingRules.text(clientClaim,100);GlPostingRules.text(canonicalStaffRealm,255);
            if(maximumBusinessLifetime==null || maximumBusinessLifetime.isZero() || maximumBusinessLifetime.isNegative())
                throw new IllegalArgumentException("Explicit finite business approval policy required");
        }
    }
    private final Object ownership=new Object(), expectedKey=new Object();
    private record Expected(GlFinancialApprovalPermit permit, AuditEvent event) {}
    private final SecurityContextPort security;
    private final PolicyEvaluationPort policies;
    private final Profile profile;
    private final Clock clock;
    private final LongSupplier nanos;
    public CurrentGlFinancialApprovalAuthorityImpl(SecurityContextPort security,PolicyEvaluationPort policies,
            Profile profile,Clock clock,LongSupplier nanos) {
        this.security=Objects.requireNonNull(security);this.policies=Objects.requireNonNull(policies);
        this.profile=Objects.requireNonNull(profile);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
    }
    public String approvalIssuer(){return profile.approvalIssuer();}
    @Override public Mono<GlFinancialApprovalPermit> authorize(Action action,UUID tenant,UUID book,UUID approval,
            GlPostingRules.Validated intent,long businessUntil,R2dbcOperationBudget original) {
        return authorizeMapped(action,tenant,book,approval,intent,businessUntil,null,original);
    }
    @Override public Mono<GlFinancialApprovalPermit> authorizeMapped(Action action,UUID tenant,UUID book,UUID approval,
            GlPostingRules.Validated intent,long businessUntil,GlLedgerMappingSnapshotRules.Validated mapping,R2dbcOperationBudget original) {return Mono.defer(()->{
        original.remaining();
        Instant observed=clock.instant();long before=nanos.getAsLong();
        for(UUID id:List.of(tenant,book,approval))GlPostingRules.uuid(id.toString());
        if(!GlPostingRules.validate(tenant.toString(),book.toString(),intent.request()).equals(intent))throw denied();
        if(mapping!=null){
            if(!GlLedgerMappingSnapshotRules.validate(mapping.snapshot()).equals(mapping))throw denied();
            GlLedgerMappingSnapshotRules.matches(tenant.toString(),book.toString(),mapping,intent);
        }
        if(action==Action.REQUEST)GlFinancialApprovalRules.businessUntil(businessUntil,intent.request().notAfterEpochMillis(),observed,profile.maximumBusinessLifetime());
        else if((action==Action.CHECK || action==Action.CONSUME) && (businessUntil<=observed.toEpochMilli()
                || businessUntil>intent.request().notAfterEpochMillis()
                || businessUntil>observed.plus(profile.maximumBusinessLifetime()).toEpochMilli()))throw denied();
        return security.currentPrincipal().switchIfEmpty(Mono.error(denied())).flatMap(principal->{
            AuditActor actor=actor(principal);Instant token=expires(principal);
            Instant until=token.isBefore(observed.plusSeconds(5))?token:observed.plusSeconds(5);
            var budget=original.intersect(R2dbcOperationBudget.observed(observed,until,before,Duration.ofSeconds(5),clock,nanos));
            Instant appendUntil=observed.plus(budget.remaining());
            var context=context(approval,intent,businessUntil,mapping);
            return evaluate(principal,actionName(action),tenant,book,context,budget).flatMap(authorization->{
                Mono<Optional<PolicyEvaluationProvenance>> audit=mutation(action)
                    ?evaluate(principal,"accounting.gl.approval.audit",tenant,book,context,budget).map(Optional::of)
                    :Mono.just(Optional.empty());
                return audit.flatMap(provenance->Flux.fromIterable(accounts(intent,mapping)).flatMap(account->
                    evaluate(principal,actionName(action),tenant,book,merge(context,"accountId",account),budget)
                        .map(value->Map.entry(account,value)),8).collectMap(Map.Entry::getKey,Map.Entry::getValue).flatMap(accountDecisions->
                    currentActor(actor,token,budget).thenReturn(new GlFinancialApprovalPermit(ownership,action,tenant,book,approval,
                        intent,businessUntil,actor,token,appendUntil,budget,accountDecisions,authorization,provenance.orElse(null),mapping))));
            }).timeout(budget.remaining());
        }).timeout(Duration.ofSeconds(5));
    });}
    @Override public Mono<Void> revalidate(GlFinancialApprovalPermit permit) {return Mono.defer(()->{
        own(permit);permit.budget().remaining();
        return currentPrincipal(permit).flatMap(principal->{
            var context=context(permit.approval(),permit.intent(),permit.businessUntil(),permit.mapping().orElse(null));
            return evaluate(principal,actionName(permit.action()),permit.tenant(),permit.book(),context,permit.budget())
                .filter(value->value.policyRevision().equals(permit.authorization().policyRevision()))
                .switchIfEmpty(Mono.error(denied())).then(mutation(permit.action())?
                    evaluate(principal,"accounting.gl.approval.audit",permit.tenant(),permit.book(),context,permit.budget())
                        .filter(value->value.policyRevision().equals(permit.audit().policyRevision())).switchIfEmpty(Mono.error(denied())).then():Mono.empty())
                .thenMany(Flux.fromIterable(permit.accountDecisions().entrySet()).flatMap(entry->
                    evaluate(principal,actionName(permit.action()),permit.tenant(),permit.book(),merge(context,"accountId",entry.getKey()),permit.budget())
                        .filter(value->value.policyRevision().equals(entry.getValue().policyRevision())).switchIfEmpty(Mono.error(denied())),8)).then();
        }).then(currentActor(permit.actor(),permit.tokenExpires(),permit.budget()));
    });}
    @Override public <T> Mono<T> within(GlFinancialApprovalPermit permit,AuditEvent expected,Supplier<Mono<T>> append) {
        own(permit);
        if(!mutation(permit.action()) || !expected.actor().equals(permit.actor())
                || !expected.stream().resourceTenantId().equals(permit.tenant())
                || !expected.stream().resourceId().equals(permit.approval())
                || !expected.stream().service().equals("core-common-accounting-gl")
                || !expected.stream().resourceType().equals("financial-approval")
                || expected.outcome()!=AuditEvent.Outcome.COMPLETED
                || !expected.action().equals(actionName(permit.action()))
                || !expected.policyRevision().equals(permit.audit().policyRevision()))return Mono.error(denied());
        return Mono.defer(append).contextWrite(context->context.put(expectedKey,new Expected(permit,expected)));
    }
    @Override public Mono<org.fireflyframework.core.audit.AuditAppendAuthority.Permit> requireCurrent(AuditEvent event) {
        return Mono.deferContextual(context->{
            if(!context.hasKey(expectedKey))return Mono.error(denied());
            Expected expected=context.get(expectedKey);var permit=expected.permit();own(permit);
            if(!event.equals(expected.event()))return Mono.error(denied());
            return currentActor(permit.actor(),permit.tokenExpires(),permit.budget()).thenReturn(
                new org.fireflyframework.core.audit.AuditAppendAuthority.Permit(event.stream(),event.actor(),permit.commandUntil()));
        });
    }
    private Mono<SecurityPrincipal> currentPrincipal(GlFinancialApprovalPermit permit){
        return security.currentPrincipal().filter(value->actor(value).equals(permit.actor()) && expires(value).equals(permit.tokenExpires()))
                .switchIfEmpty(Mono.error(denied())).timeout(permit.budget().remaining());
    }
    private Mono<Void> currentActor(AuditActor expected,Instant token,R2dbcOperationBudget budget){
        budget.remaining();return security.currentPrincipal().filter(value->actor(value).equals(expected)&&expires(value).equals(token))
                .switchIfEmpty(Mono.error(denied())).doOnNext(ignored->budget.remaining()).then().timeout(budget.remaining());
    }
    private AuditActor actor(SecurityPrincipal principal){
        GlAccountingAuthority.requireCurrent(principal);
        if(!profile.identityIssuer().equals(principal.issuer()) || !"STAFF".equals(principal.claims().get("principal_kind")))throw denied();
        Object staff=principal.claims().get("staff_actor_id"),client=principal.claims().get(profile.clientClaim());
        if(!(staff instanceof String staffId)||!(client instanceof String clientId))throw denied();
        GlPostingRules.uuid(staffId);GlPostingRules.text(clientId,255);GlPostingRules.text(principal.tenantId(),255);
        return new AuditActor(principal.issuer(),principal.subject(),clientId,principal.tenantId(),profile.canonicalStaffRealm(),UUID.fromString(staffId));
    }
    private Instant expires(SecurityPrincipal value){
        if(!(value.claims().get("exp") instanceof Instant until)||!until.isAfter(clock.instant()))throw denied();return until;
    }
    private Mono<PolicyEvaluationProvenance> evaluate(SecurityPrincipal principal,String action,UUID tenant,UUID book,
            Map<String,Object> values,R2dbcOperationBudget budget){
        budget.remaining();var resource=values.get("accountId") instanceof String account?"accounting/account/"+account:"accounting/book/"+book;
        return policies.evaluate(principal,action,resource,merge(merge(values,"tenantId",tenant.toString()),"bookId",book.toString()))
            .filter(value->value.decision().granted() && value.decision().obligations().isEmpty() && value.provenance().isPresent())
            .switchIfEmpty(Mono.error(denied())).map(value->{budget.remaining();var p=value.provenance().orElseThrow();
                if(!p.policyRevision().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))throw denied();return p;}).timeout(budget.remaining());
    }
    private static Map<String,Object> context(UUID approval,GlPostingRules.Validated intent,long until,GlLedgerMappingSnapshotRules.Validated mapping){
        Map<String,Object> result=Map.of("approvalId",approval.toString(),"intentDigest",intent.intentDigest(),"canonicalIntent",intent.canonicalIntent(),
                "economicIntentDigest",intent.economicIntentDigest(),"sourceClaimKey",intent.sourceClaimKey(),"businessUntil",until);
        if(mapping==null)return result;
        return merge(merge(merge(result,"mappingSnapshotFormat",GlLedgerMappingSnapshotRules.FORMAT),"canonicalMappingSnapshot",mapping.canonical()),
                "mappingSnapshotSha256",mapping.snapshotSha256());
    }
    private static Map<String,Object> merge(Map<String,Object> values,String key,Object value){var result=new HashMap<>(values);result.put(key,value);return Map.copyOf(result);}
    private static Set<String> accounts(GlPostingRules.Validated intent,GlLedgerMappingSnapshotRules.Validated mapping){var result=new TreeSet<String>();for(var entry:intent.request().entries())for(var line:entry.lines())result.add(line.accountId());if(mapping!=null)result.addAll(GlLedgerMappingSnapshotRules.accounts(mapping));return Set.copyOf(result);}
    private static boolean mutation(Action action){return action==Action.REQUEST||action==Action.CHECK||action==Action.REVOKE;}
    private static String actionName(Action action){return "accounting.gl.approval."+action.name().toLowerCase(Locale.ROOT);}
    private void own(GlFinancialApprovalPermit permit){if(permit==null||!permit.issuedBy(ownership))throw denied();}
    private static org.springframework.web.server.ResponseStatusException denied(){return GlFinancialApprovalRules.refused();}
}
