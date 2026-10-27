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

package io.fireflysoftware.core.accounting.models.entities.posting;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(schema = "public", value = "gl_native_journal_action")
public record GlNativeJournalAction(
        @Id UUID actionId,
        UUID tenantId,
        UUID bookId,
        String kind,
        String intentDigest,
        String canonicalIntent,
        String economicDigest,
        String canonicalEconomicIntent,
        String sourceClaimKey,
        UUID[] accountIds,
        long notAfterEpochMillis,
        UUID receiptId,
        String actorIssuer,
        String actorSubject,
        long authorityUntilEpochMillis,
        UUID postingId,
        String requestShape,
        String originalRequest,
        @org.springframework.data.annotation.ReadOnlyProperty java.time.Instant recordedAt) {
    public GlNativeJournalAction(
            UUID actionId,
            UUID tenantId,
            UUID bookId,
            String kind,
            String intentDigest,
            String canonicalIntent,
            String economicDigest,
            String canonicalEconomicIntent,
            String sourceClaimKey,
            UUID[] accountIds,
            long notAfterEpochMillis,
            UUID receiptId,
            String actorIssuer,
            String actorSubject,
            long authorityUntilEpochMillis,
            UUID postingId,
            String requestShape,
            String originalRequest) {
        this(
                actionId,
                tenantId,
                bookId,
                kind,
                intentDigest,
                canonicalIntent,
                economicDigest,
                canonicalEconomicIntent,
                sourceClaimKey,
                accountIds,
                notAfterEpochMillis,
                receiptId,
                actorIssuer,
                actorSubject,
                authorityUntilEpochMillis,
                postingId,
                requestShape,
                originalRequest,
                null);
    }

    public GlNativeJournalAction {
        accountIds = accountIds.clone();
    }

    @Override
    public UUID[] accountIds() {
        return accountIds.clone();
    }
}
