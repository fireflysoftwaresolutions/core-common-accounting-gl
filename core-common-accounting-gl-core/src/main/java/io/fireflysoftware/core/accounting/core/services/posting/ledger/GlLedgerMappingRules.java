/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.core.accounting.core.services.posting.ledger;

import io.fireflysoftware.core.accounting.core.services.posting.GlPostingRules;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.GlPostingLine;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.*;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.EnrollmentSnapshot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure classification using a supplied retained snapshot; current approval is not inferred from its digest. */
public final class GlLedgerMappingRules {
    private GlLedgerMappingRules() {}

    public static MappedCommit mapCommit(GlLedgerSourceRules.VerifiedReceipt source, MappingSnapshot mapping) {
        try {
            require(source != null && source.identity().kind() == ReceiptKind.COMMIT);
            require(mapping != null && mapping.reference() != null && mapping.currency() != null);
            require(source.identity().tenant().equals(mapping.tenant()));
            GlPostingRules.uuid(mapping.tenant());
            uuid(mapping.bookId());
            var reference = mapping.reference();
            uuid(reference.id());
            GlPostingRules.version(reference.version(), true);
            GlPostingRules.digestField(reference.digest());
            require(reference.purpose() == MappingPurpose.INTERNAL_CUSTOMER_TRANSFER);
            require(mapping.source() != null && mapping.destination() != null);
            var effect = source.envelope().value().effect();
            bind(mapping.source().ledger(), effect.source());
            bind(mapping.destination().ledger(), effect.destination());
            var sourceLeaf = mapping.source().leaf();
            var destinationLeaf = mapping.destination().leaf();
            require(sourceLeaf != null && destinationLeaf != null);
            require(!sourceLeaf.accountId().equals(destinationLeaf.accountId()));
            var leaves = Set.of(sourceLeaf.accountId(), destinationLeaf.accountId());
            var controls = new HashMap<UUID, Long>();
            leaf(sourceLeaf, mapping, leaves, controls);
            leaf(destinationLeaf, mapping, leaves, controls);

            var currency = mapping.currency();
            uuid(currency.id());
            GlPostingRules.version(currency.version(), true);
            GlPostingRules.digestField(currency.digest());
            uuid(currency.admissionId());
            GlPostingRules.version(currency.admissionVersion(), true);
            GlPostingRules.currency(currency.masters());
            require(currency.scale() >= 0 && currency.scale() <= 10);
            require(effect.source().currencyPolicy().equals(currency.ledgerPolicy())
                    && effect.destination().currencyPolicy().equals(currency.ledgerPolicy())
                    && effect.source().currencyScale() == currency.scale()
                    && effect.destination().currencyScale() == currency.scale()
                    && effect.currency().equals(currency.masters().isoCode()));
            String amount = effect.sourceMinorUnits();
            GlPostingRules.minor(amount);
            require(amount.equals(effect.destinationMinorUnits()));
            return new MappedCommit(source.identity(), mapping, List.of(
                    line("001", sourceLeaf, currency, "DR", amount),
                    line("002", destinationLeaf, currency, "CR", amount)));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Unsupported or incomplete ledger accounting mapping", failure);
        }
    }

    private static void bind(LedgerAccountReference reference, EnrollmentSnapshot snapshot) {
        require(reference != null && snapshot.accountId().equals(reference.accountId())
                && snapshot.enrollmentId().equals(reference.enrollmentId())
                && snapshot.contractId().equals(reference.contractId()));
    }

    private static void leaf(PostingLeaf leaf, MappingSnapshot mapping,
            Set<UUID> postingLeaves, Map<UUID, Long> controls) {
        require(mapping.tenant().equals(leaf.tenant()) && mapping.bookId().equals(leaf.bookId()));
        uuid(leaf.accountId());
        GlPostingRules.version(leaf.version(), false);
        require("POSTING".equals(leaf.role()) && "LIABILITY".equals(leaf.sourceType()));
        require(leaf.controlAncestors() != null && !leaf.controlAncestors().isEmpty()
                && leaf.controlAncestors().size() <= 32);
        var seen = new HashSet<UUID>();
        for (var control : leaf.controlAncestors()) {
            require(control != null);
            uuid(control.accountId());
            GlPostingRules.version(control.version(), false);
            require(!postingLeaves.contains(control.accountId()) && seen.add(control.accountId()));
            var previous = controls.putIfAbsent(control.accountId(), control.version());
            require(previous == null || previous == control.version());
        }
    }

    private static GlPostingLine line(String key, PostingLeaf leaf, CurrencyCrosswalk currency,
            String side, String amount) {
        return new GlPostingLine(key, leaf.accountId().toString(), leaf.version(),
                currency.admissionId().toString(), currency.admissionVersion(), currency.masters(),
                currency.scale(), side, amount, "Internal customer transfer");
    }

    private static void uuid(UUID value) {
        require(value != null);
        GlPostingRules.uuid(value.toString());
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Exact retained mapping required");
    }
}
