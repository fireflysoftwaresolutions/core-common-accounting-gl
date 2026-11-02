/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

public record GlBankOwnerScope(String tenantId, String bankId, String enrollmentId, long version,
        String institutionId, String issuerAuthorityId) {
    public GlBankOwnerScope {
        for (String id : new String[]{tenantId, bankId, enrollmentId, institutionId, issuerAuthorityId})
            GlPostingRules.uuid(id);
        if (version != 1) throw new IllegalArgumentException("Initial Bank owner version required");
    }

    public String resource() {
        return "tenant/" + tenantId + "/BANK_ENROLLMENT/" + bankId + "/OWNER/" + enrollmentId + "/VERSION/" + version;
    }
}
