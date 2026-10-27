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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.*;
import java.util.function.*;

class GlNativeBudgetTest {
    static Mono<?> pending(
            GlNativeContextTest.Driver d,
            GlAccountingAuthority.Admission a,
            Function<Object, Mono<String>> work) {
        var tx =
                new GlAccountingTransaction(
                        new R2dbcTransactionManager(d),
                        new GlAccountingDatabaseContext(DatabaseClient.create(d)));
        return assertDoesNotThrow(
                () ->
                        (Mono<?>)
                                tx.getClass()
                                        .getMethod(
                                                "executeWitnessed",
                                                GlAccountingAuthority.Admission.class,
                                                Function.class)
                                        .invoke(tx, a, work));
    }

    static GlNativeContextTest.Driver oneSecond(GlAccountingAuthority.Admission a) {
        var d = GlNativeContextTest.driver(a);
        d.nativeRow.put("db_now_ms", System.currentTimeMillis());
        d.nativeRow.put("effective_until_ms", System.currentTimeMillis() + 1000);
        return d;
    }

    static Mono<?> service(GlPostingApplicationServiceTest.Fixture f, String operation) {
        var service = f.service();
        return assertDoesNotThrow(
                () ->
                        (Mono<?>)
                                service.getClass()
                                        .getMethod(
                                                operation, String.class, String.class, byte[].class)
                                        .invoke(
                                                service,
                                                GlPostingApplicationServiceTest.TENANT.toString(),
                                                GlPostingApplicationServiceTest.BOOK.toString(),
                                                GlPostingIntentCodec.encode(
                                                                GlPostingApplicationServiceTest
                                                                        .TENANT
                                                                        .toString(),
                                                                GlPostingApplicationServiceTest.BOOK
                                                                        .toString(),
                                                                f.request)
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)));
    }

    @Test
    void stalledWorkIsCancelledWithinOriginalNativeWindowAndRemainsUncertain() {
        var a = GlNativeContextTest.admission();
        var d = oneSecond(a);
        var cancelled = new AtomicInteger();
        StepVerifier.withVirtualTime(
                        () ->
                                pending(
                                        d,
                                        a,
                                        op ->
                                                Mono.<String>never()
                                                        .doOnCancel(cancelled::incrementAndGet)))
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(
                        e -> assertEquals("CompletionUncertain", e.getClass().getSimpleName()))
                .verify(Duration.ofSeconds(1));
        assertEquals(1, cancelled.get());
        assertEquals(0, d.commits);
    }

    @Test
    void stalledCommitCannotKeepTheTransactionPublisherOrDriverUnbounded() {
        var a = GlNativeContextTest.admission();
        var d = oneSecond(a);
        d.stallCommit = true;
        StepVerifier.withVirtualTime(() -> pending(d, a, op -> Mono.just("private-value")))
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(
                        e -> {
                            assertEquals("CompletionUncertain", e.getClass().getSimpleName());
                            assertFalse(e.toString().contains("private-value"));
                        })
                .verify(Duration.ofSeconds(1));
        assertEquals(1, d.commits);
        assertEquals(1, d.commitCancelled);
    }

    @Test
    void stalledNativeGuardHasAFiniteTechnicalBoundWithoutInventingNativeAuthority() {
        var a = GlNativeContextTest.admission();
        var d = oneSecond(a);
        d.stallGuard = true;
        var work = new AtomicInteger();
        StepVerifier.withVirtualTime(
                        () ->
                                pending(
                                        d,
                                        a,
                                        op -> {
                                            work.incrementAndGet();
                                            return Mono.just("value");
                                        }))
                .thenAwait(Duration.ofSeconds(31))
                .expectErrorSatisfies(
                        e -> assertEquals("CompletionUncertain", e.getClass().getSimpleName()))
                .verify(Duration.ofSeconds(1));
        assertEquals(0, work.get());
        assertEquals(1, d.guardCancelled);
    }

    @Test
    void stalledFinalPolicyUsesOriginalWindowAndPreservesConfirmedCommit() {
        var f = new GlPostingApplicationServiceTest.Fixture();
        f.nativeLeaseMillis = 1000;
        f.afterFinancialCommit = () -> f.policyNever = true;
        StepVerifier.withVirtualTime(() -> service(f, "post"))
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(
                        e -> {
                            assertEquals(
                                    "GlPostingCommitKnownException", e.getClass().getSimpleName());
                            assertNull(e.getCause());
                            assertFalse(e.toString().contains(f.action.receiptId().toString()));
                        })
                .verify(Duration.ofSeconds(1));
        assertTrue(f.policyCancelled.get() > 0);
        assertEquals(7, f.rows.size());
    }

    @Test
    void stalledRecoveryDeliveryCannotReceiveAFreshPolicyTimeoutLease() {
        var f = new GlPostingApplicationServiceTest.Fixture();
        f.post();
        f.nativeLeaseMillis = 1000;
        f.afterReadCommit = () -> f.policyNever = true;
        StepVerifier.withVirtualTime(() -> service(f, "recover"))
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(
                        e ->
                                assertNotEquals(
                                        "GlPostingCommitKnownException",
                                        e.getClass().getSimpleName()))
                .verify(Duration.ofSeconds(1));
        assertTrue(f.policyCancelled.get() > 0);
        assertEquals(7, f.rows.size());
    }

    @Test
    void stalledRollbackCleanupIsBoundedAndNeverReportedAsNoEffect() {
        var a = GlNativeContextTest.admission();
        var d = oneSecond(a);
        d.stallRollback = true;
        StepVerifier.withVirtualTime(
                        () ->
                                pending(
                                        d,
                                        a,
                                        op ->
                                                Mono.error(
                                                        new IllegalStateException(
                                                                "synthetic rejected work"))))
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(
                        e -> assertEquals("CompletionUncertain", e.getClass().getSimpleName()))
                .verify(Duration.ofSeconds(1));
        assertEquals(0, d.commits);
        assertEquals(1, d.rollbackCancelled);
    }
}
