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

package io.fireflysoftware.core.accounting.core.services.journal.impl;

import io.fireflysoftware.core.accounting.core.services.journal.LegacyJournalGuard;
import io.fireflysoftware.core.accounting.models.repositories.journal.LegacyAccountUsageRepository;
import io.fireflysoftware.core.accounting.models.repositories.journal.LegacyJournalBatchStateRepository;
import io.fireflysoftware.core.accounting.models.repositories.journal.LegacyJournalEntryStateRepository;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.UUID;

@Component("legacyJournalGuard")
public final class LegacyJournalGuardImpl implements LegacyJournalGuard {
    private final LegacyJournalBatchStateRepository batches;
    private final LegacyJournalEntryStateRepository entries;
    private final LegacyAccountUsageRepository accounts;

    public LegacyJournalGuardImpl(
            LegacyJournalBatchStateRepository batches,
            LegacyJournalEntryStateRepository entries,
            LegacyAccountUsageRepository accounts) {
        this.batches = batches;
        this.entries = entries;
        this.accounts = accounts;
    }

    @Override
    public Mono<Void> batch(UUID batchId, boolean writable) {
        return batches.findById(batchId)
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()))
                .flatMap(state -> requireDraft(state.status(), state.posted(), writable));
    }

    @Override
    public Mono<Void> entry(UUID batchId, UUID entryId, boolean writable) {
        return entries.findByIdAndBatchId(entryId, batchId)
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()))
                .flatMap(state -> requireDraft(state.status(), state.posted(), writable));
    }

    @Override
    public Mono<Void> account(UUID accountId) {
        return accounts.findById(accountId)
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()))
                .flatMap(state -> state.historical()
                        ? Mono.error(LegacyJournalGuard.conflict()) : Mono.empty());
    }

    private static Mono<Void> requireDraft(String status, Boolean posted, boolean writable) {
        return writable && (!"OPEN".equals(status) || Boolean.TRUE.equals(posted))
                ? Mono.error(LegacyJournalGuard.conflict()) : Mono.empty();
    }
}
