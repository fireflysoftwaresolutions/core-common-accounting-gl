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

import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.*;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.*;
import io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec;

/** Canonical integrity and exact selection only; authenticated owner observation is a separate boundary. */
public final class GlLedgerSourceRules {
    private GlLedgerSourceRules() {}

    public static final class VerifiedReceipt {
        private final MonetaryReceiptEnvelope envelope;
        private final SourceIdentity identity;

        private VerifiedReceipt(MonetaryReceiptEnvelope envelope, SourceIdentity identity) {
            this.envelope = envelope;
            this.identity = identity;
        }

        public MonetaryReceiptEnvelope envelope() { return envelope; }
        public SourceIdentity identity() { return identity; }
    }

    public static VerifiedReceipt verifyCommit(EvidenceView view, ReceiptSelection selection) {
        verifyEvidence(view);
        return select(view.commit(), selection, ReceiptKind.COMMIT);
    }

    public static VerifiedReceipt verifyReversal(EvidenceView view, ReceiptSelection selection) {
        verifyEvidence(view);
        require(view.reversal() != null, "Linked reversal evidence required");
        return select(view.reversal(), selection, ReceiptKind.REVERSE);
    }

    private static void verifyEvidence(EvidenceView view) {
        require(view != null && view.evidenceStatus() == EvidenceStatus.RETAINED,
                "Retained monetary evidence required");
        FundsReceiptCodec.verify(view.acceptedReserve());
        FundsReceiptCodec.verify(view.commit());
        require(view.commit().value().effectKind() == EffectKind.COMMIT,
                "Original COMMIT evidence required");
        require(view.acceptedReserve().canonical().equals(
                FundsReceiptCodec.canonicalAccepted(view.commit().value().acceptedReserve())),
                "Accepted reserve evidence mismatch");
        if (view.reversal() != null) {
            FundsReceiptCodec.verify(view.reversal());
            FundsReceiptCodec.validateReversal(view.reversal().value(), view.commit().value());
        }
    }

    private static VerifiedReceipt select(MonetaryReceiptEnvelope envelope,
            ReceiptSelection selection, ReceiptKind kind) {
        require(selection != null, "Exact monetary receipt selection required");
        var value = envelope.value();
        require(value.tenant().equals(selection.tenant())
                        && value.reservationId().equals(selection.reservationId())
                        && value.receiptId().equals(selection.receiptId())
                        && value.actionId().equals(selection.actionId())
                        && value.postingId().equals(selection.postingId())
                        && envelope.canonical().digest().equals(selection.receiptDigest()),
                "Selected monetary receipt mismatch");
        return new VerifiedReceipt(envelope, new SourceIdentity(value.tenant(), value.reservationId(),
                value.operationId(), value.receiptId(), value.postingId(), kind,
                envelope.canonical().digest(), value.effectRecordedAtEpochMillis()));
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
