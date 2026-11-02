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

package io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger;

import io.fireflysoftware.core.accounting.intefaces.dtos.posting.GlCurrencyReference;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.GlPostingLine;
import java.util.List;
import java.util.UUID;

/** Retained source and mapping data; none of these values establishes current authority. */
public final class GlLedgerSourceContracts {
    private GlLedgerSourceContracts() {}

    public enum ReceiptKind { COMMIT, REVERSE }
    public enum MappingPurpose { INTERNAL_CUSTOMER_TRANSFER }

    public record ReceiptSelection(String tenant, UUID reservationId, UUID receiptId,
            UUID actionId, UUID postingId, String receiptDigest) {}

    public record SourceIdentity(String tenant, UUID reservationId, UUID operationId,
            UUID receiptId, UUID postingId, ReceiptKind kind, String receiptDigest,
            long occurredAtEpochMillis) {}

    public record LedgerAccountReference(UUID accountId, UUID enrollmentId, UUID contractId) {}
    public record ControlReference(UUID accountId, long version) {}

    public record PostingLeaf(String tenant, UUID bookId, UUID accountId, long version,
            String role, String sourceType, List<ControlReference> controlAncestors) {
        public PostingLeaf {
            controlAncestors = controlAncestors == null ? null : List.copyOf(controlAncestors);
        }
    }

    public record AccountBinding(LedgerAccountReference ledger, PostingLeaf leaf) {}
    public record MappingReference(UUID id, long version, String digest, MappingPurpose purpose) {}

    /** A retained crosswalk reference requiring independent owner verification before admission. */
    public record CurrencyCrosswalk(UUID id, long version, String digest, String ledgerPolicy,
            int scale, GlCurrencyReference masters, UUID admissionId, long admissionVersion) {}

    public record MappingSnapshot(String tenant, UUID bookId, MappingReference reference,
            AccountBinding source, AccountBinding destination, CurrencyCrosswalk currency) {}

    public record MappedCommit(SourceIdentity source, MappingSnapshot mapping, List<GlPostingLine> lines) {
        public MappedCommit { lines = List.copyOf(lines); }
    }
}
