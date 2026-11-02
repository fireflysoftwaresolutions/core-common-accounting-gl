/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fireflysoftware.core.org.sdk.invoker.ApiClient;
import io.fireflysoftware.core.org.sdk.model.BankEnrollmentOwnerView;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GlBankOwnerObservationRules {
    public static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final long MAX_TIME = 253402300799999L;
    private static final Set<String> USES = Set.of("ACCOUNTING_BOOK", "CASH_OPERATIONS", "PRODUCT_ISSUANCE");
    private static final Set<String> OWNER_FIELDS = Set.of("schemaVersion", "readKind", "tenantId", "bankId", "enrollmentId", "enrollmentVersion",
            "institutionId", "issuerAuthorityId", "allowedUses", "control", "ownershipGenerationAtAdmission", "bankGenerationAtAdmission",
            "sourceGenerationAtAdmission", "approvedRevision", "recordedAtEpochMillis", "localCurrent", "observations", "currentUntilEpochMillis");
    private static final Set<String> REVISION_FIELDS = Set.of("schemaVersion", "proposalId", "revision", "expectedBankGeneration", "previousOwner",
            "originalEnrollmentTargetJson", "canonicalEnrollmentTarget", "enrollmentTargetDigest", "reason", "allowedUses", "evidenceReferences");
    private static final Set<String> TARGET_FIELDS = Set.of("schemaVersion", "actionId", "mode", "bankId", "enrollmentId", "destinationTenantId",
            "institutionId", "issuerAuthorityId", "use", "expectedOwnershipGeneration", "expectedSourceGeneration", "existingEnrollmentId",
            "expectedEnrollmentVersion", "expectedEnrollmentControlVersion", "grantDefinition", "expectedGrantHeadVersion", "expectedGrantControlVersion",
            "commandDeadlineEpochMillis");
    private static final Set<String> OBSERVATION_FIELDS = Set.of("tenantId", "institutionId", "issuerAuthorityId", "requestedUse", "state", "definition",
            "control", "headVersion", "tenantEligibility", "observedAtEpochMillis", "validUntilEpochMillis", "denialReason");
    private static final Set<String> ELIGIBILITY_FIELDS = Set.of("tenantId", "tenantGeneration", "statusId", "statusGeneration", "tenantActive",
            "statusEnabled", "statusCode", "eligibleNow", "lastActionId");
    private static final List<String> TARGET_ORDER = List.of("schemaVersion", "actionId", "mode", "bankId", "enrollmentId",
            "destinationTenantId", "institutionId", "issuerAuthorityId", "use", "expectedOwnershipGeneration", "expectedSourceGeneration",
            "existingEnrollmentId", "expectedEnrollmentVersion", "expectedEnrollmentControlVersion", "grantDefinition", "expectedGrantHeadVersion",
            "expectedGrantControlVersion", "commandDeadlineEpochMillis");
    private static final List<String> DEFINITION_ORDER = List.of("tenantId", "target", "definitionEncoding", "canonicalDefinition", "definitionDigest", "recordedAtEpochMillis");
    private static final List<String> GRANT_ORDER = List.of("actionId", "proposalId", "proposalRevision", "grantId", "institutionId", "issuerAuthorityId",
            "version", "expectedGrantHeadVersion", "uses", "effectiveFromEpochMillis", "effectiveToEpochMillis", "reason", "evidenceReferences");
    private static final List<String> EPOCH_ORDER = List.of("tenantGeneration", "statusId", "statusGeneration");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final com.fasterxml.jackson.databind.ObjectMapper SDK_JSON = ApiClient.createDefaultMapper(null);

    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_BODY_BYTES)
                .maxNestingDepth(40).maxNumberLength(20).build());
    }

    private GlBankOwnerObservationRules() { }

    /** Structural snapshot only; authentication and current caller policy are separate. */
    public record Snapshot(GlBankOwnerExpectation expectation, String originalJson, long ownerCurrentUntilEpochMillis,
            long currentSourceGeneration) {
        @Override public String toString() { return "GlBankOwnerSnapshot"; }
    }

    public static Snapshot checked(GlBankOwnerExpectation expected, byte[] original, long receivedAtEpochMillis) {
        try {
            requireExpectation(expected);
            require(receivedAtEpochMillis > 0 && receivedAtEpochMillis < MAX_TIME);
            var value = parse(original); object(value, OWNER_FIELDS); var scope = expected.scope();
            require(number(value, "schemaVersion") == 1 && "CURRENT".equals(text(value, "readKind")));
            requireScope(scope, value);
            require(number(value, "enrollmentVersion") == scope.version()
                    && number(value, "ownershipGenerationAtAdmission") == expected.ownershipGeneration()
                    && number(value, "bankGenerationAtAdmission") == expected.bankGeneration()
                    && number(value, "sourceGenerationAtAdmission") == expected.sourceGeneration()
                    && uses(value.get("allowedUses")).equals(expected.allowedUses()));
            var control = value.get("control");
            object(control, Set.of("state", "controlVersion", "admissionActionId", "admissionActionTenantId", "lastActionId", "lastActionTenantId"));
            require("ACTIVE".equals(text(control, "state")) && number(control, "controlVersion") == expected.controlVersion()
                    && expected.admissionActionId().equals(text(control, "admissionActionId"))
                    && expected.admissionActionId().equals(text(control, "lastActionId"))
                    && scope.tenantId().equals(text(control, "admissionActionTenantId"))
                    && scope.tenantId().equals(text(control, "lastActionTenantId")));
            var local = value.get("localCurrent"); object(local, Set.of("ownershipGeneration", "bankGeneration", "sourceGeneration"));
            require(number(local, "ownershipGeneration") == expected.ownershipGeneration()
                    && number(local, "bankGeneration") == expected.bankGeneration()
                    && number(local, "sourceGeneration") >= expected.sourceGeneration());
            var retained = value.get("approvedRevision"); object(retained, Set.of("encoding", "originalJson", "canonical", "digest"));
            require("FF_ORGANIZATION_BANK_ENROLLMENT_REVISION_V2".equals(text(retained, "encoding"))
                    && expected.approvedRevisionDigest().equals(text(retained, "digest"))
                    && GlPostingRules.digest(text(retained, "canonical")).equals(expected.approvedRevisionDigest()));
            var revision = parse(text(retained, "originalJson").getBytes(StandardCharsets.UTF_8)); object(revision, REVISION_FIELDS);
            require(number(revision, "schemaVersion") == 2 && number(revision, "revision") >= 1 && revision.get("previousOwner").isNull()
                    && Math.addExact(number(revision, "expectedBankGeneration"), 1) == expected.bankGeneration()
                    && uses(revision.get("allowedUses")).equals(expected.allowedUses()));
            GlPostingRules.uuid(text(revision, "proposalId"));
            var target = parse(text(revision, "originalEnrollmentTargetJson").getBytes(StandardCharsets.UTF_8)); object(target, TARGET_FIELDS);
            require(number(target, "schemaVersion") == 2 && "ENROLL".equals(text(target, "mode"))
                    && expected.admissionActionId().equals(text(target, "actionId")) && scope.tenantId().equals(text(target, "destinationTenantId"))
                    && scope.bankId().equals(text(target, "bankId")) && scope.enrollmentId().equals(text(target, "enrollmentId"))
                    && scope.institutionId().equals(text(target, "institutionId")) && scope.issuerAuthorityId().equals(text(target, "issuerAuthorityId"))
                    && expected.allowedUses().contains(text(target, "use")) && number(target, "expectedOwnershipGeneration") == 0
                    && number(target, "expectedSourceGeneration") == expected.sourceGeneration()
                    && number(target, "expectedGrantHeadVersion") == expected.grantHeadVersion()
                    && number(target, "expectedGrantControlVersion") == expected.grantControlVersion()
                    && target.get("existingEnrollmentId").isNull() && target.get("expectedEnrollmentVersion").isNull()
                    && target.get("expectedEnrollmentControlVersion").isNull());
            String targetCanonical = token(JSON.getNodeFactory().textNode("FF_ORGANIZATION_BANK_ENROLLMENT_TARGET_V2")) + token(target);
            require(targetCanonical.equals(text(revision, "canonicalEnrollmentTarget"))
                    && GlPostingRules.digest(targetCanonical).equals(text(revision, "enrollmentTargetDigest")));
            var revisionBody = List.of(revision.get("proposalId"), revision.get("revision"), revision.get("expectedBankGeneration"),
                    revision.get("previousOwner"), JSON.getNodeFactory().textNode(targetCanonical), revision.get("reason"),
                    revision.get("allowedUses"), revision.get("evidenceReferences"));
            String revisionCanonical = token(JSON.getNodeFactory().textNode("FF_ORGANIZATION_BANK_ENROLLMENT_REVISION_V2")) + recordToken(revisionBody);
            require(revisionCanonical.equals(text(retained, "canonical")));
            boundedText(text(revision, "reason"), 4096); references(revision.get("evidenceReferences"));
            var definition = parse(expected.grantDefinitionJson().getBytes(StandardCharsets.UTF_8));
            require(definition.equals(target.get("grantDefinition"))); requireDefinition(expected, definition);
            // Original execution time is a retained fact, never a fresh current-read deadline.
            long recorded = timestamp(value, "recordedAtEpochMillis");
            require(recorded > 0 && recorded <= receivedAtEpochMillis && recorded <= timestamp(target, "commandDeadlineEpochMillis"));
            var observations = value.get("observations");
            require(observations.isArray() && observations.size() == expected.allowedUses().size());
            var seen = new HashSet<String>(); JsonNode eligibility = null; long earliest = MAX_TIME;
            for (var observation : observations) {
                object(observation, OBSERVATION_FIELDS);
                requireGrantScope(scope, observation);
                String use = text(observation, "requestedUse");
                require(expected.allowedUses().contains(use) && seen.add(use) && "CURRENT_AUTHORIZED".equals(text(observation, "state"))
                        && definition.equals(observation.get("definition")) && number(observation, "headVersion") == expected.grantHeadVersion()
                        && observation.get("denialReason").isNull());
                var upstreamControl = observation.get("control"); object(upstreamControl, Set.of("state", "controlVersion", "lastActionId"));
                require("ACTIVE".equals(text(upstreamControl, "state")) && number(upstreamControl, "controlVersion") == expected.grantControlVersion());
                GlPostingRules.uuid(text(upstreamControl, "lastActionId"));
                var live = observation.get("tenantEligibility"); object(live, ELIGIBILITY_FIELDS);
                var epoch = definition.path("target").path("eligibilityEpoch");
                require(scope.tenantId().equals(text(live, "tenantId")) && number(live, "tenantGeneration") == number(epoch, "tenantGeneration")
                        && text(live, "statusId").equals(text(epoch, "statusId")) && number(live, "statusGeneration") == number(epoch, "statusGeneration")
                        && live.get("tenantActive").isBoolean() && live.get("tenantActive").booleanValue()
                        && live.get("statusEnabled").isBoolean() && live.get("statusEnabled").booleanValue()
                        && live.get("eligibleNow").isBoolean() && live.get("eligibleNow").booleanValue() && "ACTIVE".equals(text(live, "statusCode")));
                if (!live.get("lastActionId").isNull()) GlPostingRules.uuid(text(live, "lastActionId"));
                if (eligibility == null) eligibility = live; else require(eligibility.equals(live));
                long observed = timestamp(observation, "observedAtEpochMillis"), until = timestamp(observation, "validUntilEpochMillis");
                var grant = definition.path("target").path("grant");
                require(observed <= receivedAtEpochMillis && observed >= timestamp(grant, "effectiveFromEpochMillis")
                        && observed < timestamp(grant, "effectiveToEpochMillis") && until > receivedAtEpochMillis
                        && until <= Math.addExact(observed, 30000) && until <= timestamp(grant, "effectiveToEpochMillis"));
                earliest = Math.min(earliest, until);
            }
            long until = timestamp(value, "currentUntilEpochMillis");
            require(until > receivedAtEpochMillis && until <= earliest && until <= Math.addExact(receivedAtEpochMillis, 30000));
            SDK_JSON.readValue(original, BankEnrollmentOwnerView.class);
            String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(original)).toString();
            return new Snapshot(expected, raw, until, number(local, "sourceGeneration"));
        } catch (Exception error) {
            throw bad();
        }
    }

    public static void requireExpectation(GlBankOwnerExpectation value) {
        if (value == null) throw bad();
        GlPostingRules.uuid(value.actorTenantId()); GlPostingRules.uuid(value.bookId()); GlPostingRules.uuid(value.admissionActionId());
        GlPostingRules.digestField(value.preparationIntentDigest()); GlPostingRules.digestField(value.approvedRevisionDigest());
        require(value.ownershipGeneration() == 1 && value.bankGeneration() > 0 && value.bankGeneration() < Long.MAX_VALUE
                && value.sourceGeneration() >= 0 && value.sourceGeneration() < Long.MAX_VALUE && value.controlVersion() == 0
                && value.grantHeadVersion() > 0 && value.grantHeadVersion() < Long.MAX_VALUE && value.grantControlVersion() >= 0
                && value.grantControlVersion() < Long.MAX_VALUE && !value.allowedUses().isEmpty() && value.allowedUses().size() <= 3
                && value.allowedUses().contains("ACCOUNTING_BOOK") && USES.containsAll(value.allowedUses())
                && new HashSet<>(value.allowedUses()).size() == value.allowedUses().size()
                && value.allowedUses().equals(value.allowedUses().stream().sorted().toList()));
    }

    private static void requireDefinition(GlBankOwnerExpectation expected, JsonNode value) throws Exception {
        object(value, Set.of("tenantId", "target", "definitionEncoding", "canonicalDefinition", "definitionDigest", "recordedAtEpochMillis"));
        require(expected.scope().tenantId().equals(text(value, "tenantId")) && "FF_TENANT_INSTITUTION_GRANT_DEFINITION_V2".equals(text(value, "definitionEncoding"))
                && GlPostingRules.digest(text(value, "canonicalDefinition")).equals(text(value, "definitionDigest")));
        var target = value.get("target"); object(target, Set.of("grant", "eligibilityEpoch"));
        String issue = token(JSON.getNodeFactory().textNode("FF_TENANT_INSTITUTION_GRANT_V2"))
                + recordToken(List.of(value.get("tenantId"), JSON.getNodeFactory().textNode("GRANT_ISSUE"), target));
        String canonical = token(JSON.getNodeFactory().textNode("FF_TENANT_INSTITUTION_GRANT_DEFINITION_V2"))
                + recordToken(List.of(value.get("tenantId"), JSON.getNodeFactory().textNode(issue), value.get("recordedAtEpochMillis")));
        require(canonical.equals(text(value, "canonicalDefinition")));
        var epoch = target.get("eligibilityEpoch"); object(epoch, Set.of("tenantGeneration", "statusId", "statusGeneration"));
        require(number(epoch, "tenantGeneration") >= 0 && number(epoch, "statusGeneration") >= 0); GlPostingRules.uuid(text(epoch, "statusId"));
        var grant = target.get("grant");
        object(grant, Set.of("actionId", "proposalId", "proposalRevision", "grantId", "institutionId", "issuerAuthorityId", "version",
                "expectedGrantHeadVersion", "uses", "effectiveFromEpochMillis", "effectiveToEpochMillis", "reason", "evidenceReferences"));
        for (String key : List.of("actionId", "proposalId", "grantId")) GlPostingRules.uuid(text(grant, key));
        boundedText(text(grant, "reason"), 4096); references(grant.get("evidenceReferences"));
        require(expected.scope().institutionId().equals(text(grant, "institutionId")) && expected.scope().issuerAuthorityId().equals(text(grant, "issuerAuthorityId"))
                && number(grant, "version") == expected.grantHeadVersion() && number(grant, "expectedGrantHeadVersion") == expected.grantHeadVersion() - 1
                && number(grant, "proposalRevision") > 0 && uses(grant.get("uses")).containsAll(expected.allowedUses())
                && timestamp(grant, "effectiveFromEpochMillis") < timestamp(grant, "effectiveToEpochMillis"));
    }

    // The public owner V2 byte grammar uses record declaration order, never JSON member order.
    private static String token(JsonNode value) {
        require(value != null);
        if (value.isNull()) return "N;";
        if (value.isBoolean()) return value.booleanValue() ? "B1;" : "B0;";
        if (value.isIntegralNumber() && value.canConvertToLong()) return "I" + value.longValue() + ";";
        if (value.isTextual()) {
            String text = value.textValue();
            require(text.codePoints().noneMatch(c -> c >= 0xd800 && c <= 0xdfff || c == 0xfffd || Character.isISOControl(c)));
            return "S" + text.getBytes(StandardCharsets.UTF_8).length + ":" + text;
        }
        if (value.isArray()) {
            var result = new StringBuilder("A" + value.size() + ":");
            value.forEach(item -> result.append(token(item)));
            return result.toString();
        }
        require(value.isObject());
        var fields = new HashSet<String>(); value.fieldNames().forEachRemaining(fields::add);
        for (var order : List.of(TARGET_ORDER, DEFINITION_ORDER, GRANT_ORDER, EPOCH_ORDER, List.of("grant", "eligibilityEpoch")))
            if (fields.equals(Set.copyOf(order))) return recordToken(order.stream().map(value::get).toList());
        throw bad();
    }

    private static String recordToken(List<JsonNode> values) {
        var result = new StringBuilder("R" + values.size() + ":");
        values.forEach(value -> result.append(token(value)));
        return result.toString();
    }

    private static void boundedText(String value, int maximum) {
        require(!value.isBlank() && value.length() <= maximum);
        token(JSON.getNodeFactory().textNode(value));
    }

    private static void references(JsonNode values) {
        require(values != null && values.isArray() && values.size() > 0 && values.size() <= 32);
        var seen = new HashSet<String>();
        for (var value : values) {
            require(value.isTextual() && seen.add(value.textValue()));
            boundedText(value.textValue(), 512);
        }
    }

    private static void requireScope(GlBankOwnerScope expected, JsonNode value) {
        requireGrantScope(expected, value);
        require(expected.bankId().equals(text(value, "bankId")) && expected.enrollmentId().equals(text(value, "enrollmentId")));
    }
    private static void requireGrantScope(GlBankOwnerScope expected, JsonNode value) {
        require(expected.tenantId().equals(text(value, "tenantId")) && expected.institutionId().equals(text(value, "institutionId"))
                && expected.issuerAuthorityId().equals(text(value, "issuerAuthorityId")));
    }
    private static JsonNode parse(byte[] value) throws Exception {
        require(value != null && value.length > 0 && value.length <= MAX_BODY_BYTES);
        return JSON.readTree(value);
    }
    private static void object(JsonNode value, Set<String> fields) {
        require(value != null && value.isObject()); var actual = new HashSet<String>(); value.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(fields));
    }
    private static String text(JsonNode object, String key) {
        var value = object.get(key); require(value != null && value.isTextual()); return value.textValue();
    }
    private static long number(JsonNode object, String key) {
        var value = object.get(key); require(value != null && value.isIntegralNumber() && value.canConvertToLong());
        long result = value.longValue(); require(result >= 0 && result < Long.MAX_VALUE); return result;
    }
    private static long timestamp(JsonNode object, String key) {
        var value = object.get(key); require(value != null && value.isIntegralNumber() && value.canConvertToLong());
        long result = value.longValue(); require(result >= -2208988800000L && result <= MAX_TIME); return result;
    }
    private static List<String> uses(JsonNode value) {
        require(value != null && value.isArray() && value.size() > 0 && value.size() <= 3); var result = new ArrayList<String>();
        for (var item : value) { require(item.isTextual() && USES.contains(item.textValue())); result.add(item.textValue()); }
        require(new HashSet<>(result).size() == result.size() && result.equals(result.stream().sorted().toList())); return List.copyOf(result);
    }
    private static void require(boolean value) { if (!value) throw bad(); }
    private static IllegalArgumentException bad() { return new IllegalArgumentException("Current Bank owner response required"); }
}
