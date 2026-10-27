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
import static io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.*;
import static org.junit.jupiter.api.Assertions.*;

import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.*;
import io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec;
import org.junit.jupiter.api.Test;

class GlLedgerSourceRulesTest {
    static ReceiptSelection selection(MonetaryReceiptEnvelope envelope) {
        var value = envelope.value();
        return new ReceiptSelection(value.tenant(), value.reservationId(), value.receiptId(),
                value.actionId(), value.postingId(), envelope.canonical().digest());
    }

    @Test
    void verifiesActualEnvelopeAndBindsAllSelectedIdentities() {
        var view = evidence();
        var verified = GlLedgerSourceRules.verifyCommit(view, selection(view.commit()));
        assertEquals(view.commit(), verified.envelope());
        assertEquals(view.commit().value().operationId(), verified.identity().operationId());
        assertEquals(view.commit().canonical().digest(), verified.identity().receiptDigest());
        assertEquals(view.commit().value().effectRecordedAtEpochMillis(), verified.identity().occurredAtEpochMillis());
    }

    @Test
    void everySelectionMismatchIsRejectedRatherThanTreatingAnyBalancedReceiptAsSource() {
        var view = evidence();
        var valid = selection(view.commit());
        var variants = java.util.List.of(
                new ReceiptSelection(id(99).toString(), valid.reservationId(), valid.receiptId(), valid.actionId(), valid.postingId(), valid.receiptDigest()),
                new ReceiptSelection(TENANT, id(99), valid.receiptId(), valid.actionId(), valid.postingId(), valid.receiptDigest()),
                new ReceiptSelection(TENANT, valid.reservationId(), id(99), valid.actionId(), valid.postingId(), valid.receiptDigest()),
                new ReceiptSelection(TENANT, valid.reservationId(), valid.receiptId(), id(99), valid.postingId(), valid.receiptDigest()),
                new ReceiptSelection(TENANT, valid.reservationId(), valid.receiptId(), valid.actionId(), id(99), valid.receiptDigest()),
                new ReceiptSelection(TENANT, valid.reservationId(), valid.receiptId(), valid.actionId(), valid.postingId(), "f".repeat(64)));
        for (var variant : variants) assertThrows(IllegalArgumentException.class,
                () -> GlLedgerSourceRules.verifyCommit(view, variant));
    }

    @Test
    void unavailableMissingOrIncoherentEvidenceCannotBecomeMonetaryProof() {
        var view = evidence();
        var selection = selection(view.commit());
        assertThrows(IllegalArgumentException.class, () -> GlLedgerSourceRules.verifyCommit(null, selection));
        assertThrows(IllegalArgumentException.class, () -> GlLedgerSourceRules.verifyCommit(
                new EvidenceView(EvidenceStatus.HISTORICAL_UNAVAILABLE, view.acceptedReserve(), view.commit(), null, null), selection));
        assertThrows(IllegalArgumentException.class, () -> GlLedgerSourceRules.verifyCommit(
                new EvidenceView(EvidenceStatus.RETAINED, view.acceptedReserve(), null, null, null), selection));
        var otherAccepted = new AcceptedReserve(TENANT, id(90), view.acceptedReserve().value().intent(),
                ACCEPTED_AT, ACCEPTED_AT + 60000, view.acceptedReserve().value().source(), view.acceptedReserve().value().destination());
        assertThrows(IllegalArgumentException.class, () -> GlLedgerSourceRules.verifyCommit(
                new EvidenceView(EvidenceStatus.RETAINED, FundsReceiptCodec.envelope(otherAccepted), view.commit(), null, null), selection));
    }

    @Test
    void commitAndLinkedReversalRemainSeparateVerifiedSources() {
        var view = evidence(2, "USD-CUSTOMER", "USD-CUSTOMER", true);
        var commit = GlLedgerSourceRules.verifyCommit(view, selection(view.commit()));
        var reverse = GlLedgerSourceRules.verifyReversal(view, selection(view.reversal()));
        assertNotEquals(commit.identity().receiptId(), reverse.identity().receiptId());
        assertEquals(ReceiptKind.REVERSE, reverse.identity().kind());
        assertThrows(IllegalArgumentException.class, () -> GlLedgerSourceRules.verifyReversal(evidence(), selection(view.reversal())));
    }

    @Test
    void coherentDigestDoesNotEstablishOwnerAuthenticationOrCurrentAuthority() {
        var view = evidence();
        var original = view.commit().value();
        var historicalActor = new MonetaryReceipt(original.receiptId(), original.eventId(), original.tenant(),
                original.reservationId(), original.operationId(), original.effectKind(), original.actionId(),
                original.acceptedReserve(), original.postingId(), null, null, original.effectRecordedAtEpochMillis(),
                original.effect(), new Actor("https://another-historical-issuer.example", "another-historical-subject"));
        var envelope = FundsReceiptCodec.envelope(historicalActor);
        var result = GlLedgerSourceRules.verifyCommit(new EvidenceView(EvidenceStatus.RETAINED,
                view.acceptedReserve(), envelope, null, null), selection(envelope));
        assertEquals("another-historical-subject", result.envelope().value().actor().subject());
        assertFalse(java.util.Arrays.stream(result.getClass().getMethods())
                .anyMatch(method -> java.util.Set.of("authenticated", "authorized", "principal", "executor").contains(method.getName())));
    }
}
