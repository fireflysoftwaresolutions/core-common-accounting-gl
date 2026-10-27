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

import static io.fireflysoftware.core.accounting.core.services.posting.ledger.GlLedgerReceiptContractTest.*;
import static io.fireflysoftware.core.accounting.core.services.posting.ledger.GlLedgerSourceRulesTest.selection;
import static org.junit.jupiter.api.Assertions.*;

import static io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.*;

import io.fireflysoftware.core.accounting.core.services.posting.GlPostingRules;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.GlPostingRequest;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.GlPostingEntry;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.GlSourceReference;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsContracts;
import io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.GlCurrencyReference;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GlLedgerMappingRulesTest {
    static final GlCurrencyReference USD = new GlCurrencyReference(id(30).toString(), id(31).toString(),
            "USD", 1L, "a".repeat(64), "MASTERS_CURRENCY_POLICY_V1");

    static MappingSnapshot mapping() {
        return new MappingSnapshot(TENANT, id(20), new MappingReference(id(21), 1, "b".repeat(64),
                MappingPurpose.INTERNAL_CUSTOMER_TRANSFER),
                new AccountBinding(new LedgerAccountReference(id(2), id(3), id(4)),
                        leaf(22, List.of(new ControlReference(id(24), 1)))),
                new AccountBinding(new LedgerAccountReference(id(5), id(6), id(7)),
                        leaf(23, List.of(new ControlReference(id(24), 1)))),
                new CurrencyCrosswalk(id(25), 1, "c".repeat(64), "USD-CUSTOMER", 2, USD, id(26), 1));
    }

    static PostingLeaf leaf(int account, List<ControlReference> ancestors) {
        return new PostingLeaf(TENANT, id(20), id(account), 1, "POSTING", "LIABILITY", ancestors);
    }

    static MappingSnapshot change(MappingSnapshot original, AccountBinding source,
            AccountBinding destination, CurrencyCrosswalk currency) {
        return new MappingSnapshot(original.tenant(), original.bookId(), original.reference(), source, destination, currency);
    }

    @Test
    void sourceLiabilityDecreaseDebitsSourceAndIncreaseCreditsDestinationExactly() {
        var view = evidence();
        var source = GlLedgerSourceRules.verifyCommit(view, selection(view.commit()));
        var plan = GlLedgerMappingRules.mapCommit(source, mapping());
        assertEquals(List.of("DR", "CR"), plan.lines().stream().map(line -> line.side()).toList());
        assertEquals(List.of(id(22).toString(), id(23).toString()), plan.lines().stream().map(line -> line.accountId()).toList());
        assertEquals(List.of("1234", "1234"), plan.lines().stream().map(line -> line.amountMinor()).toList());
        assertEquals(source.identity(), plan.source());
        assertEquals(mapping(), plan.mapping());
        assertThrows(UnsupportedOperationException.class, () -> plan.lines().clear());
    }

    @Test
    void exactMinorUnitsBeyondJavascriptIntegerRangeReachExistingGlBalanceRules() {
        var original = evidence().commit().value();
        var oldAccepted = original.acceptedReserve();
        var amount = money("90071992547409.93");
        var intent = oldAccepted.intent();
        var accepted = new AcceptedReserve(
                TENANT, oldAccepted.reservationId(),
                new FundsContracts.Reserve(
                        intent.operationId(), intent.sourceAccountId(), intent.destinationAccountId(), amount,
                        intent.currency(), intent.ttlSeconds(), intent.notAfterEpochMillis()), ACCEPTED_AT,
                oldAccepted.expiresAtEpochMillis(), oldAccepted.source(), oldAccepted.destination());
        var effect = new MoneyEffect(
                amount, "USD", accepted.source(), accepted.destination(), "9007199254740993", "9007199254740993",
                id(2), id(5), new BalanceVector(
                        amount.add(money("10")), amount, money("10"), money("0")),
                new BalanceVector(
                        money("0"), money("0"), amount, money("0")));
        var receipt = new MonetaryReceipt(
                original.receiptId(), original.eventId(), TENANT, original.reservationId(), original.operationId(),
                original.effectKind(), original.actionId(), accepted, original.postingId(), null, null,
                original.effectRecordedAtEpochMillis(), effect, original.actor());
        var envelope = FundsReceiptCodec.envelope(receipt);
        var view = new EvidenceView(
                EvidenceStatus.RETAINED,
                FundsReceiptCodec.envelope(accepted),
                envelope, null, null);
        var plan = GlLedgerMappingRules.mapCommit(GlLedgerSourceRules.verifyCommit(view, selection(envelope)), mapping());
        assertEquals("9007199254740993", plan.lines().getFirst().amountMinor());
        var request = new GlPostingRequest(
                id(40).toString(), id(41).toString(), 1L, 1L, ACCEPTED_AT + 200000, "2026-10-20",
                receipt.effectRecordedAtEpochMillis(),
                new GlSourceReference("LEDGER", id(8).toString(),
                        id(10).toString(), 1L, envelope.canonical().digest(), "COMMIT", 0, id(12).toString(),
                        "pending-owner-admission", ACCEPTED_AT + 2000), id(21).toString(), 1L, "b".repeat(64),
                "Internal customer transfer", List.of(new GlPostingEntry(
                        "001", "Internal customer transfer", plan.lines())));
        var result = GlPostingRules.validate(TENANT, id(20).toString(), request);
        assertEquals(1, result.totals().size());
        assertEquals(plan.lines(), result.request().entries().getFirst().lines());
    }

    @Test
    void sharedControlMustKeepOneRetainedVersionAcrossBothLeaves() {
        var view = evidence();
        var verified = GlLedgerSourceRules.verifyCommit(view, selection(view.commit()));
        var m = mapping();
        var changed = new AccountBinding(m.destination().ledger(), leaf(23, List.of(new ControlReference(id(24), 2))));
        assertThrows(IllegalArgumentException.class,
                () -> GlLedgerMappingRules.mapCommit(verified, change(m, m.source(), changed, m.currency())));
    }

    @Test
    void exactAccountEnrollmentAndContractAreRequiredOnBothSides() {
        var view = evidence();
        var verified = GlLedgerSourceRules.verifyCommit(view, selection(view.commit()));
        var m = mapping();
        for (var ref : List.of(new LedgerAccountReference(id(90), id(3), id(4)),
                new LedgerAccountReference(id(2), id(90), id(4)), new LedgerAccountReference(id(2), id(3), id(90)))) {
            assertThrows(IllegalArgumentException.class, () -> GlLedgerMappingRules.mapCommit(verified,
                    change(m, new AccountBinding(ref, m.source().leaf()), m.destination(), m.currency())));
        }
        assertThrows(IllegalArgumentException.class, () -> GlLedgerMappingRules.mapCommit(verified,
                change(m, m.destination(), m.source(), m.currency())));
    }

    @Test
    void differentBookTenantNonPostingNonLiabilityOrSameLeafIsUnavailable() {
        var view = evidence();
        var verified = GlLedgerSourceRules.verifyCommit(view, selection(view.commit()));
        var m = mapping();
        for (var leaf : List.of(
                new PostingLeaf(id(90).toString(), id(20), id(22), 1, "POSTING", "LIABILITY", List.of()),
                new PostingLeaf(TENANT, id(90), id(22), 1, "POSTING", "LIABILITY", List.of()),
                new PostingLeaf(TENANT, id(20), id(22), 1, "CONTROL", "LIABILITY", List.of()),
                new PostingLeaf(TENANT, id(20), id(22), 1, "POSTING", "ASSET", List.of()), m.destination().leaf())) {
            assertThrows(IllegalArgumentException.class, () -> GlLedgerMappingRules.mapCommit(verified,
                    change(m, new AccountBinding(m.source().ledger(), leaf), m.destination(), m.currency())));
        }
    }

    @Test
    void missingOrChangedCrosswalkCannotBeInferredFromMatchingIsoCurrency() {
        var view = evidence();
        var verified = GlLedgerSourceRules.verifyCommit(view, selection(view.commit()));
        var m = mapping();
        var c = m.currency();
        var variants = new ArrayList<CurrencyCrosswalk>();
        variants.add(null);
        variants.add(new CurrencyCrosswalk(c.id(), 1, c.digest(), "USD-LATEST", 2, USD, c.admissionId(), 1));
        variants.add(new CurrencyCrosswalk(c.id(), 1, c.digest(), c.ledgerPolicy(), 3, USD, c.admissionId(), 1));
        variants.add(new CurrencyCrosswalk(c.id(), 1, c.digest(), c.ledgerPolicy(), 2,
                new GlCurrencyReference(USD.policyId(), USD.currencyId(), "EUR", 1L, USD.digest(), USD.encodingVersion()), c.admissionId(), 1));
        for (var variant : variants) assertThrows(IllegalArgumentException.class,
                () -> GlLedgerMappingRules.mapCommit(verified, change(m, m.source(), m.destination(), variant)));
    }

    @Test
    void unlikeActualPolicyLabelsAndLedgerScaleBeyondGlLimitAreNotRelabeledOrRounded() {
        var policyMismatch = evidence(2, "USD-CUSTOMER", "USD-OTHER", false);
        assertThrows(IllegalArgumentException.class, () -> GlLedgerMappingRules.mapCommit(
                GlLedgerSourceRules.verifyCommit(policyMismatch, selection(policyMismatch.commit())), mapping()));
        var scaleMismatch = evidence(11, "USD-CUSTOMER", "USD-CUSTOMER", false);
        var m = mapping();
        var c = m.currency();
        assertThrows(IllegalArgumentException.class, () -> GlLedgerMappingRules.mapCommit(
                GlLedgerSourceRules.verifyCommit(scaleMismatch, selection(scaleMismatch.commit())),
                change(m, m.source(), m.destination(), new CurrencyCrosswalk(c.id(), 1, c.digest(),
                        c.ledgerPolicy(), 11, USD, c.admissionId(), 1))));
    }

    @Test
    void reversalCannotBePassedThroughTheCommitMapping() {
        var view = evidence(2, "USD-CUSTOMER", "USD-CUSTOMER", true);
        assertThrows(IllegalArgumentException.class, () -> GlLedgerMappingRules.mapCommit(
                GlLedgerSourceRules.verifyReversal(view, selection(view.reversal())), mapping()));
    }

    @Test
    void controlPathIsFrozenAndCannotContainLeafOrDuplicateControl() {
        var controls = new ArrayList<>(List.of(new ControlReference(id(24), 1)));
        var leaf = leaf(22, controls);
        controls.clear();
        assertEquals(1, leaf.controlAncestors().size());
        var view = evidence();
        var verified = GlLedgerSourceRules.verifyCommit(view, selection(view.commit()));
        var m = mapping();
        for (var path : List.of(List.of(new ControlReference(id(22), 1)),
                List.of(new ControlReference(id(24), 1), new ControlReference(id(24), 2)))) {
            assertThrows(IllegalArgumentException.class, () -> GlLedgerMappingRules.mapCommit(verified,
                    change(m, new AccountBinding(m.source().ledger(), leaf(22, path)), m.destination(), m.currency())));
        }
    }
}
