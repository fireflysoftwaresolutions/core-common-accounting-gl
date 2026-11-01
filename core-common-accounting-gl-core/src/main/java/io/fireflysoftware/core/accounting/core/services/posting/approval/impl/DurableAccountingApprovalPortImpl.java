/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.approval.impl;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.core.services.posting.approval.*;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.GlFinancialMappingBindingRules;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import reactor.core.publisher.Mono;

/** An existing complete approval is observed, never manufactured by this posting admission port. */
public final class DurableAccountingApprovalPortImpl implements AccountingApprovalPort {
    private final GlFinancialApprovalLookupImpl lookup;
    private final GlFinancialApprovalAuthority authority;
    private final String issuer;
    private final Clock clock;
    private final LongSupplier nanos;
    public DurableAccountingApprovalPortImpl(GlFinancialApprovalLookupImpl lookup,GlFinancialApprovalAuthority authority,
            String configuredApprovalIssuer,Clock clock,LongSupplier nanos){
        this.lookup=Objects.requireNonNull(lookup);this.authority=Objects.requireNonNull(authority);
        this.issuer=Objects.requireNonNull(configuredApprovalIssuer);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
    }
    @Override public Mono<AccountingApproval> approve(SecurityPrincipal actor,AccountingApprovalIntent intent){return Mono.defer(()->{
        if(intent==null || !"post".equals(intent.operation()))throw GlFinancialApprovalRules.refused();
        GlAccountingAuthority.requireCurrent(actor);GlPostingRules.uuid(intent.tenantId());GlPostingRules.uuid(intent.bookId());
        String action=intent.exactContext().get("actionId");GlPostingRules.uuid(action);
        var observed=clock.instant();long before=nanos.getAsLong();var budget=R2dbcOperationBudget.observed(observed,observed.plusSeconds(5),before,Duration.ofSeconds(5),clock,nanos);
        var tenant=UUID.fromString(intent.tenantId());var book=UUID.fromString(intent.bookId());
        return lookup.forPosting(tenant,book,UUID.fromString(action),actor.issuer(),actor.subject(),budget)
            .filter(Optional::isPresent).switchIfEmpty(Mono.error(GlFinancialApprovalRules.refused())).flatMap(optional->{
                var snapshot=optional.orElseThrow();var definition=snapshot.definition();var control=snapshot.control();
                var decoded=GlPostingIntentCodec.decode(intent.tenantId(),intent.bookId(),definition.originalJson());
                DurableGlFinancialApprovalServiceImpl.requireIntent(definition,decoded.validated());
                if(!definition.canonicalIntent().equals(intent.canonicalIntent()) || !definition.intentDigest().equals(intent.intentDigest()))throw GlFinancialApprovalRules.refused();
                GlFinancialApprovalRules.current(definition,control,clock.instant());
                if(!"APPROVED".equals(control.state()) || control.revision()!=1 || control.approvedUntilEpochMillis()==null
                        || control.approvedUntilEpochMillis()>definition.businessUntilEpochMillis() || control.approvedUntilEpochMillis()<=clock.millis()
                        || control.checkerIssuer()==null || control.checkerSubject()==null || control.checkerStaffRealm()==null || control.checkerStaffId()==null
                        || definition.makerIssuer().equals(control.checkerIssuer())&&definition.makerSubject().equals(control.checkerSubject())
                        || definition.makerStaffRealm().equals(control.checkerStaffRealm())&&definition.makerStaffId().equals(control.checkerStaffId()))throw GlFinancialApprovalRules.refused();
                var mapping=snapshot.mapping().map(row->GlFinancialMappingBindingRules.retained(definition,row)).orElse(null);
                return authority.authorizeMapped(GlFinancialApprovalAuthority.Action.CONSUME,tenant,book,definition.id(),decoded.validated(),control.approvedUntilEpochMillis(),mapping,budget)
                    .flatMap(permit->{GlFinancialApprovalRules.maker(definition,permit.actor());
                        if(!permit.actor().issuer().equals(actor.issuer())||!permit.actor().subject().equals(actor.subject()))throw GlFinancialApprovalRules.refused();
                        return authority.revalidate(permit).thenReturn(new AccountingApproval(intent.tenantId(),intent.bookId(),"post",intent.intentDigest(),
                            actor.issuer(),actor.subject(),issuer,definition.id().toString(),GlFinancialApprovalRules.proof(definition,control),
                            Math.min(control.approvedUntilEpochMillis(),permit.commandUntil().toEpochMilli())));
                    }).doOnNext(ignored->budget.remaining());
            });
    });}
}
