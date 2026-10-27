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

package io.fireflysoftware.core.accounting.models.repositories.posting;

import io.fireflysoftware.core.accounting.models.entities.posting.*;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface GlNativePostingRepository {
    Mono<GlNativeBook> findBook(UUID tenant, UUID book);

    Mono<GlNativePeriod> findPeriod(UUID tenant, UUID book, UUID period);

    Mono<GlNativeAccount> findAccount(UUID tenant, UUID book, UUID account);

    Mono<GlNativeCurrencyAdmission> findCurrency(UUID tenant, UUID book, UUID currency);

    Mono<GlNativeMapping> findMapping(UUID tenant, UUID book, UUID mapping);

    Mono<GlNativeBookSequence> findSequence(UUID tenant, UUID book);

    Mono<Long> advanceSequence(UUID tenant, UUID book, long expected);

    Mono<GlNativeJournalAction> findAction(UUID tenant, UUID book, UUID action);

    Mono<GlNativePosting> findPosting(UUID tenant, UUID book, UUID action);

    Mono<GlNativeJournalAction> appendAction(GlNativeJournalAction row);

    Mono<GlNativePosting> appendPosting(GlNativePosting row);

    Mono<GlNativeEntry> appendEntry(GlNativeEntry row);

    Mono<GlNativeLine> appendLine(GlNativeLine row);

    Mono<GlNativeJournalEvent> appendEvent(GlNativeJournalEvent row);
}
