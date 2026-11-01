/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.approval;

import io.fireflysoftware.core.accounting.core.services.posting.GlPostingRules;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.GlLedgerMappingSnapshotRules;
import java.time.Instant;
import java.util.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.kernel.audit.AuditActor;
import org.fireflyframework.security.api.domain.PolicyEvaluationProvenance;

/** An immutable command capability; the issuing authority retains the unexposed ownership key. */
public final class GlFinancialApprovalPermit {
    private final Object owner;
    private final GlFinancialApprovalAuthority.Action action;
    private final UUID tenant, book, approval;
    private final GlPostingRules.Validated intent;
    private final long businessUntil;
    private final AuditActor actor;
    private final Instant tokenExpires, commandUntil;
    private final Map<String, PolicyEvaluationProvenance> accountDecisions;
    private final R2dbcOperationBudget budget;
    private final PolicyEvaluationProvenance authorization, audit;
    private final GlLedgerMappingSnapshotRules.Validated mapping;
    public GlFinancialApprovalPermit(Object owner, GlFinancialApprovalAuthority.Action action,
            UUID tenant, UUID book, UUID approval, GlPostingRules.Validated intent, long businessUntil,
            AuditActor actor, Instant tokenExpires, Instant commandUntil, R2dbcOperationBudget budget,
            Map<String, PolicyEvaluationProvenance> accountDecisions,
            PolicyEvaluationProvenance authorization, PolicyEvaluationProvenance audit) {
        this(owner,action,tenant,book,approval,intent,businessUntil,actor,tokenExpires,commandUntil,budget,
                accountDecisions,authorization,audit,null);
    }
    public GlFinancialApprovalPermit(Object owner, GlFinancialApprovalAuthority.Action action,
            UUID tenant, UUID book, UUID approval, GlPostingRules.Validated intent, long businessUntil,
            AuditActor actor, Instant tokenExpires, Instant commandUntil, R2dbcOperationBudget budget,
            Map<String, PolicyEvaluationProvenance> accountDecisions,
            PolicyEvaluationProvenance authorization, PolicyEvaluationProvenance audit,GlLedgerMappingSnapshotRules.Validated mapping) {
        this.owner=Objects.requireNonNull(owner); this.action=Objects.requireNonNull(action);
        this.tenant=Objects.requireNonNull(tenant); this.book=Objects.requireNonNull(book);
        this.approval=Objects.requireNonNull(approval); this.intent=Objects.requireNonNull(intent);
        this.businessUntil=businessUntil; this.actor=Objects.requireNonNull(actor);
        this.tokenExpires=Objects.requireNonNull(tokenExpires); this.commandUntil=Objects.requireNonNull(commandUntil);
        this.budget=Objects.requireNonNull(budget); this.accountDecisions=Map.copyOf(accountDecisions);
        this.authorization=Objects.requireNonNull(authorization); this.audit=audit;this.mapping=mapping;
    }
    public boolean issuedBy(Object expected) { return owner==expected; }
    public GlFinancialApprovalAuthority.Action action(){return action;}
    public UUID tenant(){return tenant;} public UUID book(){return book;} public UUID approval(){return approval;}
    public GlPostingRules.Validated intent(){return intent;} public long businessUntil(){return businessUntil;}
    public AuditActor actor(){return actor;} public Instant tokenExpires(){return tokenExpires;}
    public R2dbcOperationBudget budget(){return budget;}
    public Instant commandUntil(){return commandUntil;}
    public Optional<GlLedgerMappingSnapshotRules.Validated> mapping(){return Optional.ofNullable(mapping);}
    public Map<String, PolicyEvaluationProvenance> accountDecisions(){return accountDecisions;}
    public String accountDecisionDigest(){var fields=new ArrayList<String>();new TreeMap<>(accountDecisions).forEach((id,p)->{fields.add(id);fields.add(p.policyRevision());fields.add(p.decisionId());});return GlPostingRules.digest(GlPostingRules.canonical("GL_APPROVAL_ACCOUNT_DECISIONS_V1",fields));}
    public PolicyEvaluationProvenance authorization(){return authorization;}
    public PolicyEvaluationProvenance audit(){if(audit==null)throw GlFinancialApprovalRules.refused();return audit;}
    @Override public String toString(){return "GlFinancialApprovalPermit[redacted]";}
}
