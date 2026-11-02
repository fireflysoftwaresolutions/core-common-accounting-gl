/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlBankOwnerObservationRulesTest {
    @Test void genuineSdkShapeRetainsExactOwnerAndAllowsAnExpiredOldExecutionTarget() throws Exception {
        var f = new BankOwnerMockResponse();
        assertTrue(f.now > f.target.get("commandDeadlineEpochMillis").longValue());
        var actual = GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(f.response()), f.now);
        assertEquals(f.now + 5000, actual.ownerCurrentUntilEpochMillis());
        assertEquals(f.expectation().scope(), actual.expectation().scope());
        assertTrue(actual.originalJson().contains("approvedRevision"));
    }

    @Test void everyOwnerIdentityAndAdmissionCounterMustRemainTheExpectedOne() throws Exception {
        var f = new BankOwnerMockResponse();
        for (String key : new String[]{"tenantId", "bankId", "enrollmentId", "institutionId", "issuerAuthorityId"}) {
            var value = f.response().put(key, "00000000-0000-0000-0000-000000000999");
            assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(value), f.now), key);
        }
        for (String key : new String[]{"enrollmentVersion", "ownershipGenerationAtAdmission", "bankGenerationAtAdmission", "sourceGenerationAtAdmission"}) {
            var value = f.response(); value.put(key, value.get(key).longValue() + 1);
            assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(value), f.now), key);
        }
    }

    @Test void historySuspensionOrIncompleteCurrentCannotBecomeACurrentObservation() throws Exception {
        var f = new BankOwnerMockResponse(); var history = f.response().put("readKind", "HISTORY");
        assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(history), f.now));
        var suspended = f.response(); ((ObjectNode) suspended.get("control")).put("state", "SUSPENDED");
        assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(suspended), f.now));
        for (String name : new String[]{"localCurrent", "currentUntilEpochMillis", "observations", "approvedRevision"}) {
            var missing = f.response(); missing.remove(name);
            assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(missing), f.now), name);
        }
    }

    @Test void bankChangeAndRestorationRefusesButLaterCalendarSourceGenerationIsSeparate() throws Exception {
        var f = new BankOwnerMockResponse(); var value = f.response();
        ((ObjectNode) value.get("localCurrent")).put("sourceGeneration", f.expectation().sourceGeneration() + 2);
        assertNotNull(GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(value), f.now));
        ((ObjectNode) value.get("localCurrent")).put("bankGeneration", f.expectation().bankGeneration() + 2);
        assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(value), f.now));
    }

    @Test void missingDuplicateOrUnrelatedUseEvidenceRefusesTheWholeOwner() throws Exception {
        var f = new BankOwnerMockResponse(); var empty = f.response(); empty.putArray("observations");
        assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(empty), f.now));
        var duplicate = f.response(); duplicate.withArray("observations").add(f.observation("ACCOUNTING_BOOK"));
        assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(duplicate), f.now));
        var other = f.response(); ((ObjectNode) other.get("observations").get(0)).put("requestedUse", "CASH_OPERATIONS");
        assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(other), f.now));
    }

    @Test void grantEpochHeadControlAndCurrentEligibilityCannotBePromoted() throws Exception {
        var f = new BankOwnerMockResponse();
        for (String name : new String[]{"headVersion", "controlVersion", "tenantGeneration"}) {
            var value = f.response(); var observation = (ObjectNode) value.get("observations").get(0);
            ObjectNode target = switch (name) { case "headVersion" -> observation; case "controlVersion" -> (ObjectNode) observation.get("control"); default -> (ObjectNode) observation.get("tenantEligibility"); };
            target.put(name, target.get(name).longValue() + 1);
            assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(value), f.now), name);
        }
        var value = f.response(); ((ObjectNode) value.get("observations").get(0).get("definition").get("target").get("eligibilityEpoch")).put("tenantGeneration", 999);
        assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(value), f.now));
    }

    @Test void malformedDuplicateKeysNonintegralCountersAndExpiredBoundsRefuse() throws Exception {
        var f = new BankOwnerMockResponse();
        var duplicate = f.response().toString().replaceFirst("\\{", "{\"schemaVersion\":1,");
        assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8), f.now));
        var value = f.response().put("bankGenerationAtAdmission", "8");
        assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(value), f.now));
        var expired = f.response().put("currentUntilEpochMillis", f.now);
        assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(expired), f.now));
    }
    @Test void retainedCanonicalBytesMustDescribeTheActualFullRevisionAndTarget() throws Exception {
        var f = new BankOwnerMockResponse();
        for (String change : new String[]{"reason", "evidence", "targetDeadline", "targetUse"}) {
            var response = f.response(); var retained = (ObjectNode) response.get("approvedRevision");
            var revision = (ObjectNode) BankOwnerMockResponse.JSON.readTree(retained.get("originalJson").textValue());
            if (change.equals("reason")) revision.put("reason", "Different retained reason");
            if (change.equals("evidence")) revision.putArray("evidenceReferences").add("different-evidence");
            if (change.startsWith("target")) {
                var target = (ObjectNode) BankOwnerMockResponse.JSON.readTree(revision.get("originalEnrollmentTargetJson").textValue());
                if (change.equals("targetDeadline")) target.put("commandDeadlineEpochMillis", target.get("commandDeadlineEpochMillis").longValue() + 1);
                else target.put("use", "PRODUCT_ISSUANCE");
                revision.put("originalEnrollmentTargetJson", target.toString());
            }
            retained.put("originalJson", revision.toString());
            assertThrows(RuntimeException.class, () -> GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(response), f.now), change);
        }
    }
    @Test void genuineRecordCanonicalizationDoesNotDependOnJsonMemberOrder() throws Exception {
        var f = new BankOwnerMockResponse(); var response = f.response();
        var retained = (ObjectNode) response.get("approvedRevision");
        var original = BankOwnerMockResponse.JSON.readTree(retained.get("originalJson").textValue());
        var reordered = BankOwnerMockResponse.JSON.createObjectNode();
        var names = new java.util.ArrayList<String>(); original.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names); names.forEach(name -> reordered.set(name, original.get(name)));
        retained.put("originalJson", reordered.toString());
        assertNotNull(GlBankOwnerObservationRules.checked(f.expectation(), f.bytes(response), f.now));
    }
}
