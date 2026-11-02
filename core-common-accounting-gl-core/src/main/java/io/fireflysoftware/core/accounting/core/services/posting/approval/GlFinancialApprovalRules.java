/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.approval;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.models.entities.approval.*;
import java.time.*;
import java.util.*;
import org.fireflyframework.kernel.audit.AuditActor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Complete owner rules, independently of transport, mutable DTOs and persistence. */
public final class GlFinancialApprovalRules {
    private GlFinancialApprovalRules() {}
    public static long businessUntil(long requested, long postingCutoff, Instant now, Duration maximumLifetime) {
        GlPostingRules.time(requested); GlPostingRules.time(postingCutoff);
        if (maximumLifetime == null || maximumLifetime.isZero() || maximumLifetime.isNegative()
                || requested <= now.toEpochMilli() || requested > postingCutoff
                || requested > now.plus(maximumLifetime).toEpochMilli()) throw refused();
        return requested;
    }
    public static void maker(GlFinancialApprovalDefinition definition, AuditActor actor) {
        if (!definition.makerIssuer().equals(actor.issuer()) || !definition.makerSubject().equals(actor.subject())
                || !definition.makerPlatformTenant().equals(actor.platformTenantId())
                || !Objects.equals(definition.makerStaffRealm(), actor.staffRealm())
                || !Objects.equals(definition.makerStaffId(), actor.staffId())) throw refused();
    }
    public static void checker(GlFinancialApprovalDefinition definition, AuditActor actor) {
        if (actor.staffRealm() == null || actor.staffId() == null || definition.makerStaffRealm() == null
                || definition.makerStaffId() == null
                || definition.makerIssuer().equals(actor.issuer()) && definition.makerSubject().equals(actor.subject())
                || definition.makerStaffRealm().equals(actor.staffRealm()) && definition.makerStaffId().equals(actor.staffId())
                || !definition.makerPlatformTenant().equals(actor.platformTenantId())) throw refused();
    }
    public static void current(GlFinancialApprovalDefinition definition, GlFinancialApprovalControl control,
            Instant now) {
        if (!definition.id().equals(control.approvalId()) || !definition.tenantId().equals(control.tenantId())
                || !definition.bookId().equals(control.bookId()) || definition.businessUntilEpochMillis() <= now.toEpochMilli()
                || !Set.of("REQUESTED", "APPROVED", "REVOKED").contains(control.state())
                || control.revision() < 0 || control.revision() > 2) throw refused();
    }
    public static String transition(GlFinancialApprovalDefinition definition, String operation,
            GlFinancialApprovalControl before, String after, AuditActor actor, long until,
            String authorizationRevision, String authorizationId, String auditRevision, String auditId) {
        long revision = before == null ? 0 : Math.addExact(before.revision(), 1);
        return GlPostingRules.canonical("GL_FINANCIAL_APPROVAL_TRANSITION_V1", List.of(
                definition.tenantId().toString(), definition.bookId().toString(), definition.id().toString(),
                definition.postingActionId().toString(), definition.intentDigest(), definition.economicIntentDigest(),
                definition.sourceClaimKey(), GlPostingRules.digest(definition.originalJson()),
                definition.makerIssuer(), definition.makerSubject(), definition.makerClientId(),
                definition.makerPlatformTenant(), definition.makerStaffRealm(), definition.makerStaffId().toString(),
                Long.toString(definition.businessUntilEpochMillis()), operation, Long.toString(revision),
                before == null ? "" : before.state(), after, Long.toString(until), actor.issuer(), actor.subject(),
                actor.clientId(), actor.platformTenantId(), actor.staffRealm(), actor.staffId().toString(),
                before == null ? "" : before.lastEventId().toString(), before == null ? "" : before.lastRecordHash(),
                authorizationRevision, authorizationId, auditRevision, auditId));
    }
    public static ResponseStatusException refused() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Current exact financial-intent approval required");
    }
    public static String proof(GlFinancialApprovalDefinition definition,GlFinancialApprovalControl control) {
        return GlPostingRules.digest(GlPostingRules.canonical("GL_FINANCIAL_APPROVAL_PROOF_V1",List.of(
            definition.tenantId().toString(),definition.bookId().toString(),definition.id().toString(),definition.intentDigest(),
            Long.toString(control.revision()),control.transitionDigest(),control.lastRecordHash(),Long.toString(control.approvedUntilEpochMillis()),
            definition.makerIssuer(),definition.makerSubject())));
    }
    public static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Original approval command completion unavailable; retain its identity");
    }
}
