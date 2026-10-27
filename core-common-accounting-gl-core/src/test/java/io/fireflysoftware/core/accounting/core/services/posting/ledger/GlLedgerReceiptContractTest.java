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

import static io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.*;
import static org.junit.jupiter.api.Assertions.*;

import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsContracts;
import io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GlLedgerReceiptContractTest {
    static final String TENANT = id(1).toString();
    static final long ACCEPTED_AT = 1792537200000L;

    static UUID id(int value) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(value));
    }

    static BigDecimal money(String value) { return new BigDecimal(value); }

    static AcceptedReserve accepted(int scale, String sourcePolicy, String destinationPolicy) {
        var amount = money("12.34");
        var source = new EnrollmentSnapshot(id(2), id(3), id(4), "USD", scale,
                sourcePolicy, FundsContracts.Kind.CUSTOMER);
        var destination = new EnrollmentSnapshot(id(5), id(6), id(7), "USD", scale,
                destinationPolicy, FundsContracts.Kind.CUSTOMER);
        return new AcceptedReserve(TENANT, id(8), new FundsContracts.Reserve(id(9), id(2),
                id(5), amount, "USD", 60, ACCEPTED_AT + 60000), ACCEPTED_AT,
                ACCEPTED_AT + 60000, source, destination);
    }

    static MonetaryReceipt receipt(AcceptedReserve accepted, boolean reverse) {
        var amount = accepted.intent().amount();
        var effect = new MoneyEffect(amount, "USD", accepted.source(), accepted.destination(),
                amount.movePointRight(accepted.source().currencyScale()).toBigIntegerExact().toString(),
                amount.movePointRight(accepted.destination().currencyScale()).toBigIntegerExact().toString(),
                reverse ? id(5) : id(2), reverse ? id(2) : id(5),
                reverse ? new BalanceVector(money("87.66"), money("0"), money("100"), money("0"))
                        : new BalanceVector(money("100"), amount, money("87.66"), money("0")),
                reverse ? new BalanceVector(money("32.34"), money("0"), money("20"), money("0"))
                        : new BalanceVector(money("20"), money("0"), money("32.34"), money("0")));
        return new MonetaryReceipt(id(reverse ? 14 : 10), id(reverse ? 14 : 10), TENANT,
                id(8), id(9), reverse ? EffectKind.REVERSE : EffectKind.COMMIT,
                id(reverse ? 15 : 11), accepted, id(reverse ? 16 : 12),
                reverse ? id(10) : null, reverse ? id(12) : null,
                ACCEPTED_AT + (reverse ? 120000 : 1000), effect,
                new Actor("https://historical-ledger-issuer.example", "historical-operator"));
    }

    static EvidenceView evidence(int scale, String sourcePolicy, String destinationPolicy, boolean reverse) {
        var accepted = accepted(scale, sourcePolicy, destinationPolicy);
        return new EvidenceView(EvidenceStatus.RETAINED, FundsReceiptCodec.envelope(accepted),
                FundsReceiptCodec.envelope(receipt(accepted, false)),
                reverse ? FundsReceiptCodec.envelope(receipt(accepted, true)) : null, null);
    }

    static EvidenceView evidence() { return evidence(2, "USD-CUSTOMER", "USD-CUSTOMER", false); }

    @Test
    void actualPublicCodecRoundTripsCompleteCommitWithoutInventedOriginalLinks() {
        var envelope = evidence().commit();
        FundsReceiptCodec.verify(envelope);
        assertEquals(envelope.canonical(), FundsReceiptCodec.canonicalReceipt(
                FundsReceiptCodec.decodeReceipt(envelope.canonical().canonical())));
        assertNull(envelope.value().originalCommitReceiptId());
        assertNull(envelope.value().originalPostingId());
        assertEquals("1234", envelope.value().effect().sourceMinorUnits());
        assertEquals("FND_MONETARY_RECEIPT_V1", envelope.canonical().encoding());
        assertEquals("b14819852e4f2eaa2b69432ad6ff34b319b584dd6279c3c5f16d76254075b911",
                envelope.canonical().digest());
        assertEquals("cbfd999f6e4517c8cf2f6c8f2d320466b0b18ca9d8f7bf5950db5038c132cf3e",
                evidence().acceptedReserve().canonical().digest());
    }

    @Test
    void actualLinkedReversalPreservesOriginalAndDoesNotNetAwayTwoEvents() {
        var view = evidence(2, "USD-CUSTOMER", "USD-CUSTOMER", true);
        FundsReceiptCodec.validateReversal(view.reversal().value(), view.commit().value());
        assertNotEquals(view.commit().value().receiptId(), view.reversal().value().receiptId());
        assertEquals(view.commit().value().postingId(), view.reversal().value().originalPostingId());
        assertTrue(view.reversal().value().effectRecordedAtEpochMillis()
                > view.acceptedReserve().value().expiresAtEpochMillis());
    }

    @Test
    void actualCodecRejectsWrongDigestAndActualMoneyVector() {
        var original = evidence().commit();
        assertThrows(IllegalArgumentException.class, () -> FundsReceiptCodec.verify(
                new MonetaryReceiptEnvelope(original.value(), new CanonicalForm(
                        original.canonical().encoding(), original.canonical().canonical(), "0".repeat(64)))));
        var value = original.value();
        var effect = value.effect();
        var changed = new MoneyEffect(effect.amount(), effect.currency(), effect.source(), effect.destination(),
                effect.sourceMinorUnits(), effect.destinationMinorUnits(), effect.fromAccountId(), effect.toAccountId(),
                new BalanceVector(money("100"), money("12.34"), money("88"), money("0")), effect.destinationBalances());
        assertThrows(IllegalArgumentException.class, () -> FundsReceiptCodec.canonicalReceipt(
                new MonetaryReceipt(value.receiptId(), value.eventId(), value.tenant(), value.reservationId(),
                        value.operationId(), value.effectKind(), value.actionId(), value.acceptedReserve(),
                        value.postingId(), null, null, value.effectRecordedAtEpochMillis(), changed, value.actor())));
    }
}
