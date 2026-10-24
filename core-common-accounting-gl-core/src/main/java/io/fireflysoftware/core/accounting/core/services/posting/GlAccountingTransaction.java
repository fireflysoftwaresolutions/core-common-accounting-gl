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

package io.fireflysoftware.core.accounting.core.services.posting;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.util.function.Supplier;

@Component
public final class GlAccountingTransaction {
    private final TransactionalOperator transactions;
    private final GlAccountingDatabaseContext context;

    public GlAccountingTransaction(
            ReactiveTransactionManager manager, GlAccountingDatabaseContext context) {
        this.transactions = TransactionalOperator.create(manager);
        this.context = context;
    }

    public <T> Mono<T> execute(GlAccountingAuthority.Admission admission, Supplier<Mono<T>> work) {
        return GlAccountingBoundary.outsideTransaction(
                () ->
                        Mono.defer(
                                () -> {
                                    admission.requireCurrent();
                                    return transactions.transactional(
                                            Mono.defer(
                                                    () ->
                                                            context.establish(admission)
                                                                    .then(Mono.defer(work))
                                                                    .switchIfEmpty(
                                                                            Mono.error(
                                                                                    new ResponseStatusException(
                                                                                            HttpStatus
                                                                                                    .SERVICE_UNAVAILABLE,
                                                                                            "A retained"
                                                                                                + " accounting"
                                                                                                + " result"
                                                                                                + " is required")))
                                                                    .doOnNext(
                                                                            result ->
                                                                                    admission
                                                                                            .requireCurrent())));
                                }));
    }
}
