/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.ledger.impl;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.core.services.posting.approval.*;
import io.fireflysoftware.core.accounting.core.services.posting.approval.impl.*;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.*;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import reactor.core.publisher.Mono;

/** Reads only the exact Core-retained, independently checked binding on the original owner graph. */
public final class CoreApprovedLedgerMappingPortImpl implements GlApprovedLedgerMappingPort {
    private final GlFinancialApprovalLookupImpl lookup;
    private final GlFinancialApprovalAuthority authority;
    private final Clock clock;
    private final LongSupplier nanos;
    public CoreApprovedLedgerMappingPortImpl(GlFinancialApprovalLookupImpl lookup,GlFinancialApprovalAuthority authority,Clock clock,LongSupplier nanos) {
        this.lookup=Objects.requireNonNull(lookup);this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
    }
    @Override public Mono<Approved> current(GlAccountingAuthority.Admission admission,GlPostingRules.Validated posting) {
        return GlAccountingBoundary.outsideTransaction(()->Mono.defer(()->{
            admission.requireCurrent();if(admission.approval()==null||!"post".equals(admission.intent().operation()))throw GlFinancialApprovalRules.refused();
            String tenant=admission.intent().tenantId(),book=admission.intent().bookId();
            Instant observed=clock.instant();long before=nanos.getAsLong();
            var budget=R2dbcOperationBudget.observed(observed,Instant.ofEpochMilli(Math.min(admission.validUntilEpochMillis(),observed.plusSeconds(5).toEpochMilli())),before,Duration.ofSeconds(5),clock,nanos);
            return lookup.forPosting(UUID.fromString(tenant),UUID.fromString(book),UUID.fromString(posting.request().actionId()),
                    admission.actor().issuer(),admission.actor().subject(),budget).filter(Optional::isPresent).switchIfEmpty(Mono.error(GlFinancialApprovalRules.refused()))
                .flatMap(optional->{var retained=optional.orElseThrow();var definition=retained.definition();var control=retained.control();
                    var row=retained.mapping().orElseThrow(GlFinancialApprovalRules::refused);var snapshot=GlFinancialMappingBindingRules.retained(definition,row);
                    GlLedgerMappingSnapshotRules.matches(tenant,book,snapshot,posting);GlFinancialApprovalRules.current(definition,control,clock.instant());
                    if(!definition.canonicalIntent().equals(posting.canonicalIntent())||!definition.intentDigest().equals(posting.intentDigest())
                            ||!"APPROVED".equals(control.state())||control.revision()!=1||control.approvedUntilEpochMillis()==null
                            ||control.approvedUntilEpochMillis()>definition.businessUntilEpochMillis()||control.approvedUntilEpochMillis()<=clock.millis()
                            ||control.checkerIssuer()==null||control.checkerSubject()==null||control.checkerStaffRealm()==null||control.checkerStaffId()==null
                            ||definition.makerIssuer().equals(control.checkerIssuer())&&definition.makerSubject().equals(control.checkerSubject())
                            ||definition.makerStaffRealm().equals(control.checkerStaffRealm())&&definition.makerStaffId().equals(control.checkerStaffId())
                            ||!definition.id().toString().equals(admission.approval().approvalReference()))throw GlFinancialApprovalRules.refused();
                    String proof=GlFinancialApprovalRules.proof(definition,control);
                    if(!proof.equals(admission.approval().approvalDigest()))throw GlFinancialApprovalRules.refused();
                    return authority.authorizeMapped(GlFinancialApprovalAuthority.Action.CONSUME,definition.tenantId(),definition.bookId(),definition.id(),posting,
                            control.approvedUntilEpochMillis(),snapshot,budget).flatMap(permit->{
                        GlFinancialApprovalRules.maker(definition,permit.actor());
                        if(!permit.actor().issuer().equals(admission.actor().issuer())||!permit.actor().subject().equals(admission.actor().subject()))throw GlFinancialApprovalRules.refused();
                        return authority.revalidate(permit).then(Mono.fromSupplier(()->{budget.remaining();admission.requireCurrent();
                            return new Approved(definition.id(),row.snapshotId(),snapshot,proof,Math.min(admission.validUntilEpochMillis(),permit.commandUntil().toEpochMilli()));
                        }));
                    });
                }).timeout(budget.remaining());
        }));
    }
}
