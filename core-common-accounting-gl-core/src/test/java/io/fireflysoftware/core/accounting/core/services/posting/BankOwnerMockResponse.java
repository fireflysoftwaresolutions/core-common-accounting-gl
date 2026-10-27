/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fireflysoftware.core.org.sdk.invoker.ApiClient;
import java.util.List;

/** Controlled owner transport data from literal owner vectors; never an authority producer. */
final class BankOwnerMockResponse {
    static final com.fasterxml.jackson.databind.ObjectMapper JSON = ApiClient.createDefaultMapper(null);
    static final String ACTOR_TENANT = "00000000-0000-0000-0000-000000000201";
    static final String BOOK = "00000000-0000-0000-0000-000000000401";
    final JsonNode vector;
    final JsonNode revision;
    final JsonNode target;
    final long now;

    BankOwnerMockResponse() throws Exception {
        try (var input = getClass().getResourceAsStream("/bank-owner/bank-enrollment-revision-v2-golden.json")) {
            vector = JSON.readTree(input);
        }
        revision = JSON.readTree(vector.get("original").textValue());
        target = JSON.readTree(revision.get("originalEnrollmentTargetJson").textValue());
        now = target.get("commandDeadlineEpochMillis").longValue() + 60000;
    }

    GlBankOwnerExpectation expectation() {
        return new GlBankOwnerExpectation(ACTOR_TENANT, new GlBankOwnerScope(text("destinationTenantId"), text("bankId"), text("enrollmentId"),
                1, text("institutionId"), text("issuerAuthorityId")), BOOK, "a".repeat(64), vector.get("digest").textValue(),
                text("actionId"), 1, revision.get("expectedBankGeneration").longValue() + 1,
                target.get("expectedSourceGeneration").longValue(), 0, target.get("expectedGrantHeadVersion").longValue(),
                target.get("expectedGrantControlVersion").longValue(), java.util.stream.StreamSupport.stream(revision.get("allowedUses").spliterator(), false)
                        .map(JsonNode::textValue).toList(), target.get("grantDefinition").toString());
    }

    ObjectNode response() {
        var e = expectation(); var s = e.scope();
        var value = JSON.createObjectNode().put("schemaVersion", 1).put("readKind", "CURRENT").put("tenantId", s.tenantId())
                .put("bankId", s.bankId()).put("enrollmentId", s.enrollmentId()).put("enrollmentVersion", s.version())
                .put("institutionId", s.institutionId()).put("issuerAuthorityId", s.issuerAuthorityId())
                .put("ownershipGenerationAtAdmission", e.ownershipGeneration()).put("bankGenerationAtAdmission", e.bankGeneration())
                .put("sourceGenerationAtAdmission", e.sourceGeneration()).put("recordedAtEpochMillis", now - 70000)
                .put("currentUntilEpochMillis", now + 5000);
        var uses = value.putArray("allowedUses"); e.allowedUses().forEach(uses::add);
        value.set("control", JSON.createObjectNode().put("state", "ACTIVE").put("controlVersion", 0)
                .put("admissionActionId", e.admissionActionId()).put("admissionActionTenantId", s.tenantId())
                .put("lastActionId", e.admissionActionId()).put("lastActionTenantId", s.tenantId()));
        value.set("localCurrent", JSON.createObjectNode().put("ownershipGeneration", e.ownershipGeneration())
                .put("bankGeneration", e.bankGeneration()).put("sourceGeneration", e.sourceGeneration()));
        value.set("approvedRevision", JSON.createObjectNode().put("encoding", "FF_ORGANIZATION_BANK_ENROLLMENT_REVISION_V2")
                .put("originalJson", vector.get("original").textValue()).put("canonical", vector.get("canonical").textValue())
                .put("digest", vector.get("digest").textValue()));
        var observations = value.putArray("observations"); e.allowedUses().forEach(use -> observations.add(observation(use)));
        return value;
    }

    ObjectNode observation(String use) {
        var s = expectation().scope(); var definition = target.get("grantDefinition");
        var epoch = definition.path("target").path("eligibilityEpoch");
        var value = JSON.createObjectNode().put("tenantId", s.tenantId()).put("institutionId", s.institutionId())
                .put("issuerAuthorityId", s.issuerAuthorityId()).put("requestedUse", use).put("state", "CURRENT_AUTHORIZED")
                .put("headVersion", target.get("expectedGrantHeadVersion").longValue()).put("observedAtEpochMillis", now - 100)
                .put("validUntilEpochMillis", now + 20000).putNull("denialReason");
        value.set("definition", definition.deepCopy());
        value.set("control", JSON.createObjectNode().put("state", "ACTIVE").put("controlVersion", target.get("expectedGrantControlVersion").longValue())
                .put("lastActionId", definition.path("target").path("grant").path("actionId").textValue()));
        value.set("tenantEligibility", JSON.createObjectNode().put("tenantId", s.tenantId())
                .put("tenantGeneration", epoch.get("tenantGeneration").longValue()).put("statusId", epoch.get("statusId").textValue())
                .put("statusGeneration", epoch.get("statusGeneration").longValue()).put("tenantActive", true).put("statusEnabled", true)
                .put("statusCode", "ACTIVE").put("eligibleNow", true).putNull("lastActionId"));
        return value;
    }

    byte[] bytes(JsonNode value) throws Exception { return JSON.writeValueAsBytes(value); }
    private String text(String name) { return target.get(name).textValue(); }
}
