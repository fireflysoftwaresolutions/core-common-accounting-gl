/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

import org.fireflyframework.core.transaction.R2dbcOperationBudget;

public record GlBankOwnerObservation(GlBankOwnerObservationRules.Snapshot snapshot,
        long validUntilEpochMillis, String observerIssuer, String observerSubject,
        String platformTenantId, String clientId, R2dbcOperationBudget budget) {
    public GlBankOwnerObservation {
        java.util.Objects.requireNonNull(snapshot);
        java.util.Objects.requireNonNull(budget);
    }

    public void requireCurrent() {
        budget.remaining();
    }

    @Override public String toString() { return "GlBankOwnerObservation[finite evidence]"; }
}
