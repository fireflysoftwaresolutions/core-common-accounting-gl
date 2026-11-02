/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

import java.util.List;
import java.util.Objects;

/** Expected read references; this record conveys neither caller authority nor book approval. */
public record GlBankOwnerExpectation(String actorTenantId, GlBankOwnerScope scope, String bookId,
        String preparationIntentDigest, String approvedRevisionDigest, String admissionActionId,
        long ownershipGeneration, long bankGeneration, long sourceGeneration, long controlVersion,
        long grantHeadVersion, long grantControlVersion, List<String> allowedUses, String grantDefinitionJson) {
    public GlBankOwnerExpectation {
        Objects.requireNonNull(scope);
        allowedUses = List.copyOf(allowedUses);
        Objects.requireNonNull(grantDefinitionJson);
    }

    @Override public String toString() { return "GlBankOwnerExpectation"; }
}
