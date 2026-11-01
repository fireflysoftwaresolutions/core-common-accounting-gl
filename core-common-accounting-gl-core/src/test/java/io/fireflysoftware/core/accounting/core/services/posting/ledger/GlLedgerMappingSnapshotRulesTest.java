/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.ledger;

import static org.assertj.core.api.Assertions.*;
import static io.fireflysoftware.core.accounting.core.services.posting.ledger.GlLedgerMappingRulesTest.mapping;
import static io.fireflysoftware.core.accounting.core.services.posting.ledger.GlLedgerReceiptContractTest.id;

import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GlLedgerMappingSnapshotRulesTest {
    @Test
    void referenceDigestKeepsItsMeaningWhileNewSnapshotShaBindsTheFullCrosswalk() {
        var proposal = mapping();var first = GlLedgerMappingSnapshotRules.validate(proposal);var c = proposal.currency();
        var changed = new MappingSnapshot(proposal.tenant(), proposal.bookId(), proposal.reference(), proposal.source(), proposal.destination(),
                new CurrencyCrosswalk(c.id(), c.version(), c.digest(), "USD-OTHER-POLICY", c.scale(), c.masters(), c.admissionId(), c.admissionVersion()));
        var second = GlLedgerMappingSnapshotRules.validate(changed);
        assertThat(first.canonical()).startsWith("GL_LEDGER_MAPPING_SNAPSHOT_V1\n");
        assertThat(first.snapshotSha256()).isNotEqualTo(proposal.reference().digest()).isNotEqualTo(second.snapshotSha256());
        assertThat(second.snapshot().reference()).isEqualTo(proposal.reference());
    }

    @Test
    void changingAnyLedgerAccountEnrollmentOrContractCannotPreserveTheSnapshotDigest() {
        var proposal = mapping();var reference = proposal.source().ledger();
        var digest = GlLedgerMappingSnapshotRules.validate(proposal).snapshotSha256();
        for (var changed : List.of(new LedgerAccountReference(id(80), reference.enrollmentId(), reference.contractId()),
                new LedgerAccountReference(reference.accountId(), id(81), reference.contractId()),
                new LedgerAccountReference(reference.accountId(), reference.enrollmentId(), id(82)))) {
            var value = new MappingSnapshot(proposal.tenant(), proposal.bookId(), proposal.reference(),
                    new AccountBinding(changed, proposal.source().leaf()), proposal.destination(), proposal.currency());
            assertThat(GlLedgerMappingSnapshotRules.validate(value).snapshotSha256()).isNotEqualTo(digest);
        }
    }

    @Test
    void unknownDuplicateOrIncompleteJsonCannotBecomeARetainedSnapshot() {
        var json = GlLedgerMappingSnapshotRules.validate(mapping()).originalJson();
        var unknown = json.substring(0, json.length() - 1) + ",\"unapproved\":true}";
        var duplicate = "{\"tenant\":\"" + mapping().tenant() + "\"," + json.substring(1);
        for (var body : List.of(unknown, duplicate, "{}", json + "{}"))
            assertThatThrownBy(() -> GlLedgerMappingSnapshotRules.decode(body)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonFormattingDoesNotChangeTheExplicitSemanticEncoding() {
        var value = GlLedgerMappingSnapshotRules.validate(mapping());
        var decoded = GlLedgerMappingSnapshotRules.decode(" \n" + value.originalJson() + "\n ");
        assertThat(decoded.snapshot()).isEqualTo(value.snapshot());
        assertThat(decoded.canonical()).isEqualTo(value.canonical());
        assertThat(decoded.snapshotSha256()).isEqualTo(value.snapshotSha256());
    }

    @Test
    void incompleteOrConflictingControlPathsAndIdenticalLedgerAccountsAreRejected() {
        var p = mapping();var leaf = p.source().leaf();var control = leaf.controlAncestors().getFirst();
        for (var path : List.of(List.<ControlReference>of(), List.of(control, control),
                List.of(new ControlReference(p.destination().leaf().accountId(), 1)))) {
            var invalid = new MappingSnapshot(p.tenant(), p.bookId(), p.reference(),
                    new AccountBinding(p.source().ledger(), new PostingLeaf(leaf.tenant(), leaf.bookId(), leaf.accountId(), leaf.version(), leaf.role(), leaf.sourceType(), path)),
                    p.destination(), p.currency());
            assertThatThrownBy(() -> GlLedgerMappingSnapshotRules.validate(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        var identical = new MappingSnapshot(p.tenant(), p.bookId(), p.reference(), p.source(),
                new AccountBinding(p.source().ledger(), p.destination().leaf()), p.currency());
        assertThatThrownBy(() -> GlLedgerMappingSnapshotRules.validate(identical)).isInstanceOf(IllegalArgumentException.class);
    }
}
