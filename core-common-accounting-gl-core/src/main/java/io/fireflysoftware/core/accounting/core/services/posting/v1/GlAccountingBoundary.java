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

package io.fireflysoftware.core.accounting.core.services.posting.v1;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.util.function.Supplier;

public final class GlAccountingBoundary {
    private GlAccountingBoundary() {}

    public static <T> Mono<T> outsideTransaction(Supplier<Mono<T>> work) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .map(TransactionSynchronizationManager::isActualTransactionActive)
                .onErrorResume(NoTransactionException.class, error -> Mono.just(false))
                .flatMap(
                        active ->
                                active
                                        ? Mono.error(
                                                new ResponseStatusException(
                                                        HttpStatus.CONFLICT,
                                                        "Accounting operation requires its own"
                                                            + " transaction boundary"))
                                        : Mono.defer(work));
    }
}
