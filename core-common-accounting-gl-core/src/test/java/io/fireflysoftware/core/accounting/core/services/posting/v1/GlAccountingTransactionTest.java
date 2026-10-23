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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager;
import org.springframework.transaction.reactive.GenericReactiveTransaction;
import org.springframework.transaction.reactive.TransactionContextManager;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

class GlAccountingTransactionTest {
    static final class Manager extends AbstractReactiveTransactionManager {
        final AtomicInteger begins = new AtomicInteger(),
                commits = new AtomicInteger(),
                rollbacks = new AtomicInteger();

        protected Object doGetTransaction(TransactionSynchronizationManager manager) {
            return new Object();
        }

        protected Mono<Void> doBegin(
                TransactionSynchronizationManager m, Object tx, TransactionDefinition d) {
            return Mono.fromRunnable(begins::incrementAndGet);
        }

        protected Mono<Void> doCommit(
                TransactionSynchronizationManager m, GenericReactiveTransaction tx) {
            return Mono.fromRunnable(commits::incrementAndGet);
        }

        protected Mono<Void> doRollback(
                TransactionSynchronizationManager m, GenericReactiveTransaction tx) {
            return Mono.fromRunnable(rollbacks::incrementAndGet);
        }
    }

    static GlAccountingAuthority.Admission admission() {
        return GlAccountingDatabaseContextTest.authority()
                .authorizeAccounts(
                        GlAccountingDatabaseContextTest.intent(
                                GlAccountingDatabaseContextTest.ACTION),
                        java.util.List.of(GlAccountingDatabaseContextTest.ACCOUNT),
                        true)
                .block();
    }

    static GlAccountingDatabaseContext context() {
        var context = mock(GlAccountingDatabaseContext.class);
        when(context.establish(any()))
                .thenAnswer(
                        call ->
                                TransactionSynchronizationManager.forCurrentTransaction()
                                        .flatMap(
                                                manager ->
                                                        manager.isActualTransactionActive()
                                                                ? Mono.empty()
                                                                : Mono.error(
                                                                        new AssertionError(
                                                                                "Actual transaction"
                                                                                    + " required"))));
        return context;
    }

    @Test
    void receiptIsNotReturnedUntilCommitAndContextRunsInsideTransaction() {
        var manager = new Manager();
        var context = context();
        var tx = new GlAccountingTransaction(manager, context);
        var calls = new AtomicInteger();
        StepVerifier.create(
                        tx.execute(
                                admission(),
                                () ->
                                        Mono.fromSupplier(
                                                () -> {
                                                    calls.incrementAndGet();
                                                    assertThat(manager.commits).hasValue(0);
                                                    return "retained-receipt";
                                                })))
                .assertNext(
                        value -> {
                            assertThat(value).isEqualTo("retained-receipt");
                            assertThat(manager.commits).hasValue(1);
                        })
                .verifyComplete();
        assertThat(manager.begins).hasValue(1);
        assertThat(manager.rollbacks).hasValue(0);
        assertThat(calls).hasValue(1);
        verify(context).establish(any());
    }

    @Test
    void emptyBusinessResultRollsBackInsteadOfCommittingEmptySuccess() {
        var manager = new Manager();
        var tx = new GlAccountingTransaction(manager, context());
        StepVerifier.create(tx.execute(admission(), Mono::empty))
                .expectErrorSatisfies(
                        error -> {
                            assertThat(error)
                                    .isInstanceOfSatisfying(
                                            ResponseStatusException.class,
                                            e ->
                                                    assertThat(e.getStatusCode())
                                                            .isEqualTo(
                                                                    HttpStatus
                                                                            .SERVICE_UNAVAILABLE));
                        })
                .verify();
        assertThat(manager.commits).hasValue(0);
        assertThat(manager.rollbacks).hasValue(1);
    }

    @Test
    void failedDatabaseAdmissionRollsBackWithoutCallingBusinessWork() {
        var manager = new Manager();
        var context = context();
        var calls = new AtomicInteger();
        when(context.establish(any()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));
        StepVerifier.create(
                        new GlAccountingTransaction(manager, context)
                                .execute(
                                        admission(),
                                        () -> {
                                            calls.incrementAndGet();
                                            return Mono.just("bad");
                                        }))
                .expectError(ResponseStatusException.class)
                .verify();
        assertThat(calls).hasValue(0);
        assertThat(manager.commits).hasValue(0);
        assertThat(manager.rollbacks).hasValue(1);
    }

    @Test
    void businessErrorRollsBackAndPreservesTheOriginalFailure() {
        var manager = new Manager();
        var failure = new IllegalStateException("synthetic-only");
        StepVerifier.create(
                        new GlAccountingTransaction(manager, context())
                                .execute(admission(), () -> Mono.error(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify();
        assertThat(manager.commits).hasValue(0);
        assertThat(manager.rollbacks).hasValue(1);
    }

    @Test
    void cancellationRollsBackBeforeCompletion() {
        var manager = new Manager();
        var workEntered = new AtomicInteger();
        StepVerifier.create(
                        new GlAccountingTransaction(manager, context())
                                .execute(
                                        admission(),
                                        () -> {
                                            workEntered.incrementAndGet();
                                            return Mono.never();
                                        }))
                .thenAwait(Duration.ofMillis(1))
                .then(() -> assertThat(workEntered).hasValue(1))
                .thenCancel()
                .verify();
        assertThat(manager.commits).hasValue(0);
        assertThat(manager.rollbacks).hasValue(1);
    }

    @Test
    void ambientTransactionIsRejectedBeforeContextAndBusinessCallbacks() {
        var manager = new Manager();
        var context = context();
        var calls = new AtomicInteger();
        var admitted = admission();
        var result =
                TransactionSynchronizationManager.forCurrentTransaction()
                        .flatMap(
                                current -> {
                                    current.setActualTransactionActive(true);
                                    return new GlAccountingTransaction(manager, context)
                                            .execute(
                                                    admitted,
                                                    () -> {
                                                        calls.incrementAndGet();
                                                        return Mono.just("bad");
                                                    });
                                })
                        .contextWrite(TransactionContextManager.createTransactionContext());
        StepVerifier.create(result)
                .expectErrorSatisfies(
                        error ->
                                assertThat(error)
                                        .isInstanceOfSatisfying(
                                                ResponseStatusException.class,
                                                e ->
                                                        assertThat(e.getStatusCode())
                                                                .isEqualTo(HttpStatus.CONFLICT)))
                .verify();
        verify(context, never()).establish(any());
        assertThat(calls).hasValue(0);
        assertThat(manager.begins).hasValue(0);
    }

    @Test
    void expiryDuringWorkRollsBackBeforeFinancialAcknowledgment() {
        var manager = new Manager();
        var current = admission();
        var admitted =
                new GlAccountingAuthority.Admission(
                        current.actor(),
                        current.approval(),
                        System.currentTimeMillis() + 150,
                        current.intent(),
                        current.accountIds());
        StepVerifier.create(
                        new GlAccountingTransaction(manager, context())
                                .execute(
                                        admitted,
                                        () ->
                                                Mono.delay(Duration.ofMillis(250))
                                                        .thenReturn("uncommitted")))
                .expectError(ResponseStatusException.class)
                .verify();
        assertThat(manager.commits).hasValue(0);
        assertThat(manager.rollbacks).hasValue(1);
    }
}
