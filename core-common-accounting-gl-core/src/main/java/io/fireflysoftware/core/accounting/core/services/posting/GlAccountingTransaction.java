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

import io.fireflysoftware.core.accounting.models.repositories.posting.GlOwnedConnectionFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
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
    private final ReactiveTransactionManager manager;
    private final GlOwnedConnectionFactory ownership;
    private org.fireflyframework.core.transaction.BoundedRootTransaction shared;
    private java.time.Clock budgetClock;
    private final GlAccountingDatabaseContext context;
    private final java.util.function.LongSupplier nanos, epoch;

    @org.springframework.beans.factory.annotation.Autowired
    public GlAccountingTransaction(ReactiveTransactionManager manager, GlAccountingDatabaseContext context) {
        this(manager, context, System::nanoTime, System::currentTimeMillis);
    }

    GlAccountingTransaction(ReactiveTransactionManager manager, GlAccountingDatabaseContext context,
            java.util.function.LongSupplier nanos, java.util.function.LongSupplier epoch) {
        this.transactions=TransactionalOperator.create(manager);this.manager=manager;this.context=context;
        this.nanos=nanos;this.epoch=epoch;
        ownership=context.connectionFactory() instanceof GlOwnedConnectionFactory owned ? owned : null;
        if(ownership!=null && (!(manager instanceof R2dbcTransactionManager actual) || actual.getConnectionFactory()!=ownership))
            throw new IllegalStateException("One qualified native factory required for context and transaction manager");
    }

    public GlAccountingTransaction(ReactiveTransactionManager manager, GlAccountingDatabaseContext context,
            org.springframework.data.r2dbc.core.R2dbcEntityTemplate template) {
        this(manager,context,template,System::nanoTime,System::currentTimeMillis);
    }

    GlAccountingTransaction(ReactiveTransactionManager manager, GlAccountingDatabaseContext context,
            org.springframework.data.r2dbc.core.R2dbcEntityTemplate template,
            java.util.function.LongSupplier nanos, java.util.function.LongSupplier epoch) {
        this(manager,context,nanos,epoch);
        if(!(context.connectionFactory() instanceof org.fireflyframework.core.transaction.OwnedLeaseConnectionFactory factory)
                || !(manager instanceof R2dbcTransactionManager actual)
                || context.databaseClient()!=template.getDatabaseClient() || actual.getConnectionFactory()!=factory)
            throw new IllegalStateException("One protected native template graph required");
        var resources=org.fireflyframework.core.transaction.BoundedR2dbcResources.verify(factory,context.databaseClient(),template,actual);
        shared=new org.fireflyframework.core.transaction.impl.DefaultBoundedRootTransaction(resources,
                new org.fireflyframework.core.transaction.CleanupPolicy(java.time.Duration.ofSeconds(2),java.time.Duration.ofSeconds(2)));
        budgetClock=new java.time.Clock(){
            @Override public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
            @Override public java.time.Clock withZone(java.time.ZoneId zone){if(!java.time.ZoneOffset.UTC.equals(zone))throw new IllegalArgumentException("UTC budget clock required");return this;}
            @Override public java.time.Instant instant(){return java.time.Instant.ofEpochMilli(GlAccountingTransaction.this.epoch.getAsLong());}
        };
    }

    public record Completed<T>(T value, GlNativeOperation operation) {}
    private record WorkResult<T>(T value, GlNativeOperation operation) {}

    public <T> Mono<Completed<T>> executeWitnessed(GlAccountingAuthority.Admission admission,
            java.util.function.Function<GlNativeOperation, Mono<T>> work) {
        if(shared!=null)return executeShared(admission,work);
        return GlAccountingBoundary.outsideTransaction(() -> Mono.defer(() -> {
            admission.requireCurrent();
            long beforeNative=nanos.getAsLong();
            var workCompleted=new java.util.concurrent.atomic.AtomicBoolean();
            var insideFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var observed=reactor.core.publisher.Sinks.<GlNativeOperation>one();
            var operationRef=new java.util.concurrent.atomic.AtomicReference<GlNativeOperation>();
            long observedEpoch=epoch.getAsLong();
            long initialMillis=Math.min(30000L,Math.subtractExact(admission.validUntilEpochMillis(),observedEpoch));
            if(initialMillis<=0)return Mono.error(new CompletionUncertain());
            Mono<Long> deadline=Mono.firstWithSignal(
                    Mono.delay(java.time.Duration.ofMillis(initialMillis)),
                    observed.asMono().flatMap(operation -> Mono.delay(operation.remaining())));
            Mono<WorkResult<T>> inside=Mono.defer(() -> context.establishWithNativeTime(admission))
                    .map(stamp -> new GlNativeOperation(admission,GlNativeTimeWindow.from(stamp,beforeNative),nanos,epoch))
                    .flatMap(operation -> {
                        operation.requireCurrent();
                        operationRef.set(operation);
                        if(observed.tryEmitValue(operation).isFailure())return Mono.error(new CompletionUncertain());
                        return Mono.defer(() -> work.apply(operation)).switchIfEmpty(Mono.error(
                                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Retained witnessed result required")))
                                .map(value -> {
                                    operation.requireCurrent();
                                    workCompleted.set(true);
                                    return new WorkResult<>(value,operation);
                                });
                    }).doOnError(insideFailure::set);
            Mono<Completed<T>> completed=TransactionalOperator.create(boundedManager(operationRef)).transactional(inside)
                    .timeout(deadline,Mono.error(new CompletionUncertain()))
                    .map(result -> new Completed<>(result.value(),result.operation()));
            if(ownership!=null)completed=ownership.within(() -> {
                long elapsed=nanos.getAsLong()-beforeNative;
                long left=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(initialMillis)-elapsed;
                long now=epoch.getAsLong();
                if(elapsed<0 || left<=0 || now<observedEpoch || now>=admission.validUntilEpochMillis())throw new CompletionUncertain();
                var remaining=java.time.Duration.ofNanos(left);var operation=operationRef.get();
                if(operation!=null){var nativeRemaining=operation.remaining();if(nativeRemaining.compareTo(remaining)<0)remaining=nativeRemaining;}
                return remaining;
            },completed);
            return completed.onErrorMap(error -> !workCompleted.get() && error == insideFailure.get()
                            ? error : new CompletionUncertain());
        }));
    }

    private <T> Mono<Completed<T>> executeShared(GlAccountingAuthority.Admission admission,
            java.util.function.Function<GlNativeOperation,Mono<T>> work) {
        return GlAccountingBoundary.outsideTransaction(()->Mono.defer(()->{
            admission.requireCurrent();long beforeNative=nanos.getAsLong();long observedEpoch=epoch.getAsLong();
            long until=Math.min(admission.validUntilEpochMillis(),Math.addExact(observedEpoch,30000L));
            if(until<=observedEpoch)return Mono.error(new CompletionUncertain());
            var original=org.fireflyframework.core.transaction.R2dbcOperationBudget.observed(
                    java.time.Instant.ofEpochMilli(observedEpoch),java.time.Instant.ofEpochMilli(until),beforeNative,
                    java.time.Duration.ofSeconds(30),budgetClock,nanos);
            var operationRef=new java.util.concurrent.atomic.AtomicReference<GlNativeOperation>();
            var insideFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var workCompleted=new java.util.concurrent.atomic.AtomicBoolean();
            return shared.execute(original,new org.springframework.transaction.support.DefaultTransactionDefinition(),scope->
                Mono.defer(()->context.establishWithNativeTime(admission)).flatMap(stamp->{
                    var operation=new GlNativeOperation(admission,GlNativeTimeWindow.from(stamp,beforeNative),nanos,epoch);
                    operation.requireCurrent();operationRef.set(operation);
                    // Map the actual native duration to the original pre-native anchor; never start a fresh TTL.
                    long nativeMillis=Math.subtractExact(Math.subtractExact(stamp.effectiveUntilEpochMillis(),stamp.recordedAtEpochMillis()),1L);
                    long capUntil=Math.min(operation.admission().validUntilEpochMillis(),
                            Math.min(stamp.effectiveUntilEpochMillis(),Math.addExact(observedEpoch,nativeMillis)));
                    scope.narrow(org.fireflyframework.core.transaction.R2dbcOperationBudget.observed(
                            java.time.Instant.ofEpochMilli(observedEpoch),java.time.Instant.ofEpochMilli(capUntil),beforeNative,
                            java.time.Duration.ofSeconds(30),budgetClock,nanos));
                    return Mono.defer(()->work.apply(operation)).switchIfEmpty(Mono.error(new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,"Retained witnessed result required")))
                        .map(value->{operation.requireCurrent();workCompleted.set(true);return value;});
                }).doOnError(insideFailure::set))
                .map(completed->{
                    try {operationRef.get().requireCurrent();}
                    catch(RuntimeException expired){throw new CompletionKnown();}
                    return new Completed<>(completed.value(),operationRef.get());
                }).onErrorMap(org.fireflyframework.core.transaction.RootTransactionException.class,error->{
                    var facts=error.transactionFacts();
                    if(facts.boundaryCompleted())return new CompletionKnown();
                    var failure=insideFailure.get();
                    boolean safe=failure!=null;
                    if(!workCompleted.get()&&safe&&facts.rollbackAcknowledged()
                            && error.cleanupFact()==org.fireflyframework.core.transaction.CleanupFact.COMPLETE
                            && !facts.commitAcknowledged()&&!facts.boundaryCompleted())return failure;
                    return new CompletionUncertain();
                });
        }));
    }

    public static final class CompletionKnown extends IllegalStateException {
        private CompletionKnown(){super("Native transaction boundary completed; protected delivery unavailable");}
    }

    private ReactiveTransactionManager boundedManager(java.util.concurrent.atomic.AtomicReference<GlNativeOperation> operation) {
        return new ReactiveTransactionManager() {
            @Override public Mono<org.springframework.transaction.ReactiveTransaction> getReactiveTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return manager.getReactiveTransaction(definition);
            }
            @Override public Mono<Void> commit(org.springframework.transaction.ReactiveTransaction status) {
                return Mono.defer(() -> {
                    java.time.Duration remaining;
                    try {
                        var current=operation.get();
                        if(current==null)return cleanupUncertain(status);
                        remaining=current.remaining();
                    } catch(GlNativeTimeWindow.Expired error) {return cleanupUncertain(status);}
                    return manager.commit(status).timeout(remaining)
                            .onErrorResume(java.util.concurrent.TimeoutException.class,error -> cleanupUncertain(status));
                });
            }
            @Override public Mono<Void> rollback(org.springframework.transaction.ReactiveTransaction status) {
                return Mono.defer(() -> {
                    java.time.Duration limit=java.time.Duration.ofSeconds(2);
                    try {var current=operation.get();if(current!=null&&current.remaining().compareTo(limit)<0)limit=current.remaining();}
                    catch(GlNativeTimeWindow.Expired ignored) {}
                    return manager.rollback(status).timeout(limit);
                });
            }
            private Mono<Void> cleanupUncertain(org.springframework.transaction.ReactiveTransaction status) {
                if(status.isCompleted())return Mono.error(new CompletionUncertain());
                // Cleanup has a separate finite allowance and cannot authorize financial work.
                return manager.rollback(status).timeout(java.time.Duration.ofSeconds(2))
                        .onErrorMap(error -> new CompletionUncertain())
                        .then(Mono.error(new CompletionUncertain()));
            }
        };
    }

    public static final class CompletionUncertain extends IllegalStateException {
        private CompletionUncertain(){super("Native transaction completion uncertain; protected recovery required");}
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
