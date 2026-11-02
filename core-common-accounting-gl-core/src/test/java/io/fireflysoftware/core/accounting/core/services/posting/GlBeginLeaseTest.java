/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.core.accounting.core.services.posting;

import io.r2dbc.spi.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring begin/cancellation behavior over a networkless owned driver. */
class GlBeginLeaseTest {
    static final class Driver implements ConnectionFactory {
        int creates, begins, beginCancelled, closes, commits, closeCancelled, commitCancelled, acquireCancelled;
        boolean stallBegin=true, stallAcquire, stallClose, stallCommit;
        final GlNativeContextTest.Driver statements=new GlNativeContextTest.Driver();
        final Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
            case "isAutoCommit" -> true;
            case "getTransactionIsolationLevel" -> IsolationLevel.READ_COMMITTED;
            case "beginTransaction" -> Mono.defer(() -> { begins++; return stallBegin ? Mono.<Void>never().doOnCancel(() -> beginCancelled++) : Mono.empty(); });
            case "commitTransaction" -> Mono.defer(() -> {commits++;return stallCommit ? Mono.<Void>never().doOnCancel(() -> commitCancelled++) : Mono.empty();});
            case "close" -> Mono.defer(() -> {closes++;return stallClose ? Mono.<Void>never().doOnCancel(() -> closeCancelled++) : Mono.empty();});
            case "createStatement" -> statements.statement((String)arguments[0]);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            case "toString" -> "OwnedNetworklessGlLease";
            default -> Mono.empty();
        });
        @Override public Publisher<? extends Connection> create() { return Mono.defer(() -> { creates++; return stallAcquire ? Mono.<Connection>never().doOnCancel(() -> acquireCancelled++) : Mono.just(connection); }); }
        @Override public ConnectionFactoryMetadata getMetadata() { return () -> "PostgreSQL"; }
    }
    static GlAccountingTransaction configured(Driver driver) {
        var config=new GlNativePostingConfiguration();var template=new org.springframework.data.r2dbc.core.R2dbcEntityTemplate(driver);
        try {template=(org.springframework.data.r2dbc.core.R2dbcEntityTemplate)config.getClass().getMethod("nativeTemplate",org.springframework.data.r2dbc.core.R2dbcEntityTemplate.class).invoke(config,template);}
        catch(NoSuchMethodException legacy) {} catch(ReflectiveOperationException error) {throw new AssertionError(error);}
        return config.transaction(template);
    }
    @Test void witnessedBeginCancellationMustReleaseItsOwnCreatedLease() {
        var driver = new Driver(); var work = new AtomicInteger(); var admission = GlNativeContextTest.admission();
        StepVerifier.withVirtualTime(() -> configured(driver)
                .executeWitnessed(admission, operation -> { work.incrementAndGet(); return Mono.just("protected-value"); }))
            .thenAwait(Duration.ofSeconds(31))
            .expectErrorSatisfies(error -> { assertEquals("CompletionUncertain", error.getClass().getSimpleName()); assertFalse(error.toString().contains("protected-value")); })
            .verify(Duration.ofSeconds(1));
        assertEquals(1, driver.creates); assertEquals(1, driver.begins); assertEquals(1, driver.beginCancelled);
        assertEquals(1, driver.closes); assertEquals(0, driver.commits); assertEquals(0, work.get());
    }
    @Test void anotherFactoryLeaseIsNeverClosedByTheProtectedBeginCleanup() {
        var own=new Driver();var foreign=new Driver();Mono.from(foreign.create()).block();
        StepVerifier.withVirtualTime(() -> configured(own).executeWitnessed(GlNativeContextTest.admission(),operation -> Mono.just("protected-value")))
            .thenAwait(Duration.ofSeconds(31)).expectError(GlAccountingTransaction.CompletionUncertain.class).verify(Duration.ofSeconds(1));
        assertEquals(1,own.closes);assertEquals(1,foreign.creates);assertEquals(0,foreign.closes);
    }
    @Test void stalledCloseHasFiniteIndependentCleanupAndNoResultOrNoEffectClaim() {
        var driver=new Driver();driver.stallClose=true;
        StepVerifier.withVirtualTime(() -> configured(driver).executeWitnessed(GlNativeContextTest.admission(),operation -> Mono.just("protected-value")))
            .thenAwait(Duration.ofSeconds(34)).expectErrorSatisfies(error -> {assertEquals(GlAccountingTransaction.CompletionUncertain.class,error.getClass());assertFalse(error.toString().contains("protected-value"));})
            .verify(Duration.ofSeconds(1));
        assertEquals(1,driver.closes);assertEquals(1,driver.closeCancelled);assertEquals(0,driver.commits);
    }
    @Test void cancellationDuringProtectedBeginClosesOnlyItsOwnLeaseOnce() {
        var driver=new Driver();var values=new AtomicInteger();
        StepVerifier.withVirtualTime(() -> configured(driver).executeWitnessed(GlNativeContextTest.admission(),operation -> Mono.just("protected-value")).doOnNext(v->values.incrementAndGet()))
            .expectSubscription().thenAwait(Duration.ofMillis(100)).thenCancel().verify(Duration.ofSeconds(1));
        assertEquals(1,driver.beginCancelled);assertEquals(1,driver.closes);assertEquals(0,driver.commits);assertEquals(0,values.get());
    }
    @Test void repeatedCloseAndScopeCleanupShareOneExactLeaseClose() {
        var driver=new Driver();var factory=new io.fireflysoftware.core.accounting.models.repositories.posting.GlOwnedConnectionFactory(driver);
        StepVerifier.create(factory.within(()->Duration.ofSeconds(1),Mono.from(factory.create()).flatMap(connection->Mono.from(connection.close()).then(Mono.from(connection.close())).thenReturn("source-value"))))
            .expectNext("source-value").verifyComplete();
        assertEquals(1,driver.creates);assertEquals(1,driver.closes);
    }
    @Test void differentFactoryCannotBorrowThePrivateInvocationScope() {
        var own=new Driver();var foreign=new Driver();var a=new io.fireflysoftware.core.accounting.models.repositories.posting.GlOwnedConnectionFactory(own);var b=new io.fireflysoftware.core.accounting.models.repositories.posting.GlOwnedConnectionFactory(foreign);
        StepVerifier.create(a.within(()->Duration.ofSeconds(1),Mono.from(b.create()))).expectError(IllegalStateException.class).verify();
        assertEquals(0,own.creates);assertEquals(0,foreign.creates);assertEquals(0,foreign.closes);
    }
    @Test void protectedWorkStallUsesNativeWindowAndClosesItsLease() {
        var driver=new Driver();driver.stallBegin=false;var admission=GlNativeContextTest.admission();driver.statements.nativeRow.putAll(GlNativeContextTest.driver(admission).nativeRow);driver.statements.nativeRow.put("effective_until_ms",System.currentTimeMillis()+1000);
        var cancelled=new AtomicInteger();
        StepVerifier.withVirtualTime(() -> configured(driver).executeWitnessed(admission,operation->Mono.<String>never().doOnCancel(cancelled::incrementAndGet)))
            .thenAwait(Duration.ofSeconds(2)).expectError(GlAccountingTransaction.CompletionUncertain.class).verify(Duration.ofSeconds(1));
        assertEquals(1,cancelled.get());assertEquals(1,driver.closes);assertEquals(0,driver.commits);
    }
    @Test void protectedCommitStallCancelsDriverAndClosesItsLease() {
        var driver=new Driver();driver.stallBegin=false;driver.stallCommit=true;var admission=GlNativeContextTest.admission();driver.statements.nativeRow.putAll(GlNativeContextTest.driver(admission).nativeRow);driver.statements.nativeRow.put("effective_until_ms",System.currentTimeMillis()+1000);
        StepVerifier.withVirtualTime(() -> configured(driver).executeWitnessed(admission,operation->Mono.just("protected-value")))
            .thenAwait(Duration.ofSeconds(2)).expectError(GlAccountingTransaction.CompletionUncertain.class).verify(Duration.ofSeconds(1));
        assertEquals(1,driver.commits);assertEquals(1,driver.commitCancelled);assertEquals(1,driver.closes);
    }
    @Test void acquisitionTimeoutCannotCloseAnUncreatedOrForeignLease() {
        var driver=new Driver();driver.stallAcquire=true;
        StepVerifier.withVirtualTime(() -> configured(driver).executeWitnessed(GlNativeContextTest.admission(),operation->Mono.just("protected-value")))
            .thenAwait(Duration.ofSeconds(31)).expectError(GlAccountingTransaction.CompletionUncertain.class).verify(Duration.ofSeconds(1));
        assertEquals(1,driver.acquireCancelled);assertEquals(0,driver.begins);assertEquals(0,driver.closes);assertEquals(0,driver.commits);
    }
    @Test void realSpringOuterTimeoutCharacterizationDoesNotCloseTheBeginLease() {
        var driver = new Driver();
        StepVerifier.withVirtualTime(() -> TransactionalOperator.create(new R2dbcTransactionManager(driver)).transactional(Mono.just("protected-value")).timeout(Duration.ofSeconds(30)))
            .thenAwait(Duration.ofSeconds(31)).expectError(java.util.concurrent.TimeoutException.class).verify(Duration.ofSeconds(1));
        assertEquals(1, driver.creates); assertEquals(1, driver.begins); assertEquals(1, driver.beginCancelled); assertEquals(0, driver.closes); assertEquals(0, driver.commits);
    }
}
