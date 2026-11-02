/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.ledger;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.core.accounting.core.services.posting.GlPostingRules;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.*;
import java.util.*;

/** A complete immutable proposal encoding, distinct from the retained mapping-definition digest. */
public final class GlLedgerMappingSnapshotRules {
    public static final String FORMAT = "GL_LEDGER_MAPPING_SNAPSHOT_V1";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private GlLedgerMappingSnapshotRules() {}
    public record Validated(MappingSnapshot snapshot, String originalJson, String canonical, String snapshotSha256) {}

    public static Validated validate(MappingSnapshot snapshot) {
        try {
            Objects.requireNonNull(snapshot);GlPostingRules.uuid(snapshot.tenant());uuid(snapshot.bookId());
            var fields = new ArrayList<String>();fields.add(snapshot.tenant());fields.add(snapshot.bookId().toString());
            var reference = Objects.requireNonNull(snapshot.reference());uuid(reference.id());
            GlPostingRules.version(reference.version(), true);GlPostingRules.digestField(reference.digest());
            if (reference.purpose() != MappingPurpose.INTERNAL_CUSTOMER_TRANSFER) throw invalid();
            fields.addAll(List.of(reference.id().toString(), Long.toString(reference.version()), reference.digest(), reference.purpose().name()));
            var leaves = new HashSet<UUID>();var versions = new HashMap<UUID, Long>();
            binding(fields, snapshot, snapshot.source(), leaves, versions);
            binding(fields, snapshot, snapshot.destination(), leaves, versions);
            if (snapshot.source().ledger().accountId().equals(snapshot.destination().ledger().accountId())) throw invalid();
            for (var control : versions.keySet()) if (leaves.contains(control)) throw invalid();
            var currency = Objects.requireNonNull(snapshot.currency());uuid(currency.id());
            GlPostingRules.version(currency.version(), true);GlPostingRules.digestField(currency.digest());
            GlPostingRules.text(currency.ledgerPolicy(), 100);GlPostingRules.currency(currency.masters());
            uuid(currency.admissionId());GlPostingRules.version(currency.admissionVersion(), true);
            if (currency.scale() < 0 || currency.scale() > 10) throw invalid();
            var masters = currency.masters();
            fields.addAll(List.of(currency.id().toString(), Long.toString(currency.version()), currency.digest(), currency.ledgerPolicy(),
                    Integer.toString(currency.scale()), masters.policyId(), masters.currencyId(), masters.isoCode(), masters.version().toString(),
                    masters.digest(), masters.encodingVersion(), currency.admissionId().toString(), Long.toString(currency.admissionVersion())));
            String canonical = GlPostingRules.canonical(FORMAT, fields), json = JSON.writeValueAsString(snapshot);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576) throw invalid();
            return new Validated(snapshot, json, canonical, GlPostingRules.digest(canonical));
        } catch (Exception failure) {throw invalid();}
    }

    public static Validated decode(String json) {
        try {
            if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576) throw invalid();
            return validate(JSON.readValue(json, MappingSnapshot.class));
        } catch (Exception failure) {throw invalid();}
    }

    public static void matches(String tenant, String book, Validated snapshot, GlPostingRules.Validated posting) {
        var s = snapshot.snapshot();var r = posting.request();
        if (!s.tenant().equals(tenant) || !s.bookId().toString().equals(book)
                || !s.reference().id().toString().equals(r.mappingId()) || s.reference().version() != r.mappingVersion()
                || !s.reference().digest().equals(r.mappingDigest())) throw invalid();
        var source=r.source();
        if(!"LEDGER".equals(source.owner())||!"COMMIT".equals(source.eventKind())||source.revision()!=1L||source.effectOrdinal()!=0
                ||r.entries().size()!=1||r.entries().getFirst().lines().size()!=2)throw invalid();
        for(var id:List.of(source.objectReference(),source.effectReference(),source.financialReceiptReference(),source.provenanceReference()))GlPostingRules.uuid(id);
        var lines=r.entries().getFirst().lines();var currency=s.currency();int ordinal=0;
        for(var binding:List.of(s.source(),s.destination())){
            var line=lines.get(ordinal);ordinal++;
            if(!line.lineKey().equals(ordinal==1?"001":"002")||!line.accountId().equals(binding.leaf().accountId().toString())
                    ||line.expectedAccountVersion()!=binding.leaf().version()||!line.currencyAdmissionId().equals(currency.admissionId().toString())
                    ||line.expectedCurrencyVersion()!=currency.admissionVersion()||!line.currency().equals(currency.masters())||line.postedScale()!=currency.scale()
                    ||!line.side().equals(ordinal==1?"DR":"CR")||!line.memo().equals("Internal customer transfer"))throw invalid();
        }
        if(!lines.getFirst().amountMinor().equals(lines.getLast().amountMinor()))throw invalid();
    }

    public static String binding(UUID approval, UUID snapshotId, Validated snapshot) {
        uuid(approval);uuid(snapshotId);var r = snapshot.snapshot().reference();
        return GlPostingRules.canonical("GL_FINANCIAL_MAPPING_BINDING_V1", List.of(approval.toString(), snapshotId.toString(), FORMAT,
                snapshot.snapshotSha256(), r.id().toString(), Long.toString(r.version()), r.digest()));
    }

    public static Set<String> accounts(Validated value) {
        var result = new TreeSet<String>();
        for (var binding : List.of(value.snapshot().source(), value.snapshot().destination())) {
            result.add(binding.leaf().accountId().toString());
            for (var ancestor : binding.leaf().controlAncestors()) result.add(ancestor.accountId().toString());
        }
        return Set.copyOf(result);
    }

    private static void binding(List<String> fields, MappingSnapshot snapshot, AccountBinding binding,
            Set<UUID> leaves, Map<UUID, Long> controls) {
        Objects.requireNonNull(binding);var ledger = Objects.requireNonNull(binding.ledger());
        uuid(ledger.accountId());uuid(ledger.enrollmentId());uuid(ledger.contractId());
        var leaf = Objects.requireNonNull(binding.leaf());GlPostingRules.uuid(leaf.tenant());uuid(leaf.bookId());uuid(leaf.accountId());
        GlPostingRules.version(leaf.version(), true);
        if (!snapshot.tenant().equals(leaf.tenant()) || !snapshot.bookId().equals(leaf.bookId()) || !"POSTING".equals(leaf.role())
                || !"LIABILITY".equals(leaf.sourceType()) || !leaves.add(leaf.accountId()) || leaf.controlAncestors() == null
                || leaf.controlAncestors().isEmpty() || leaf.controlAncestors().size() > 32) throw invalid();
        fields.addAll(List.of(ledger.accountId().toString(), ledger.enrollmentId().toString(), ledger.contractId().toString(),
                leaf.tenant(), leaf.bookId().toString(), leaf.accountId().toString(), Long.toString(leaf.version()), leaf.role(), leaf.sourceType(),
                Integer.toString(leaf.controlAncestors().size())));
        var seen = new HashSet<UUID>();
        for (var control : leaf.controlAncestors()) {
            Objects.requireNonNull(control);uuid(control.accountId());GlPostingRules.version(control.version(), true);
            if (!seen.add(control.accountId())) throw invalid();
            var previous = controls.putIfAbsent(control.accountId(), control.version());
            if (previous != null && previous != control.version()) throw invalid();
            fields.addAll(List.of(control.accountId().toString(), Long.toString(control.version())));
        }
    }
    private static void uuid(UUID value) {GlPostingRules.uuid(Objects.requireNonNull(value).toString());}
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Complete retained GL ledger mapping snapshot required");}
}
