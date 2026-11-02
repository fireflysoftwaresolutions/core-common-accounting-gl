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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

class GlNativeTransactionTest {
    static Object run(
            GlNativeContextTest.Driver driver,
            GlAccountingAuthority.Admission admission,
            AtomicLong nanos,
            AtomicLong epoch,
            Function<Object, Mono<String>> work) {
        var tx =
                assertDoesNotThrow(
                        () ->
                                GlAccountingTransaction.class
                                        .getDeclaredConstructor(
                                                org.springframework.transaction
                                                        .ReactiveTransactionManager.class,
                                                GlAccountingDatabaseContext.class,
                                                java.util.function.LongSupplier.class,
                                                java.util.function.LongSupplier.class)
                                        .newInstance(
                                                new R2dbcTransactionManager(driver),
                                                new GlAccountingDatabaseContext(
                                                        DatabaseClient.create(driver)),
                                                (java.util.function.LongSupplier) nanos::get,
                                                (java.util.function.LongSupplier) epoch::get));
        var pending =
                assertDoesNotThrow(
                        () ->
                                (Mono<?>)
                                        GlAccountingTransaction.class
                                                .getMethod(
                                                        "executeWitnessed",
                                                        GlAccountingAuthority.Admission.class,
                                                        Function.class)
                                                .invoke(tx, admission, work));
        return pending.block();
    }

    @Test
    void anchorPrecedesWholeNativeGuardAndInstallation() {
        var a = GlNativeContextTest.admission();
        var d = GlNativeContextTest.driver(a);
        long epoch = System.currentTimeMillis();
        d.nativeRow.put("db_now_ms", epoch);
        d.nativeRow.put("effective_until_ms", epoch + 20);
        var n = new AtomicLong(1000000000);
        d.onGuard = () -> n.addAndGet(5000000);
        var result =
                run(
                        d,
                        a,
                        n,
                        new AtomicLong(epoch),
                        op -> {
                            assertEquals(
                                    14,
                                    ((Duration)
                                                    GlNativeTimeWindowTest.call(
                                                            op, "remaining", new Class[] {}))
                                            .toMillis());
                            return Mono.just("value");
                        });
        assertEquals("value", GlNativeTimeWindowTest.call(result, "value", new Class[] {}));
    }

    @Test
    void admissionPassedToWorkUsesNativeNarrowerBound() {
        var a = GlNativeContextTest.admission();
        var d = GlNativeContextTest.driver(a);
        long epoch = System.currentTimeMillis();
        d.nativeRow.put("db_now_ms", epoch);
        d.nativeRow.put("effective_until_ms", epoch + 1000);
        run(
                d,
                a,
                new AtomicLong(1),
                new AtomicLong(epoch),
                op -> {
                    var bounded =
                            (GlAccountingAuthority.Admission)
                                    GlNativeTimeWindowTest.call(op, "admission", new Class[] {});
                    assertEquals(epoch + 999, bounded.validUntilEpochMillis());
                    return Mono.just("value");
                });
    }

    @Test
    void completedCommitFactSurvivesWindowExpiryDuringCommit() {
        var a = GlNativeContextTest.admission();
        var d = GlNativeContextTest.driver(a);
        long epoch = System.currentTimeMillis();
        d.nativeRow.put("db_now_ms", epoch);
        d.nativeRow.put("effective_until_ms", epoch + 20);
        var n = new AtomicLong(1000000000);
        d.onCommit = () -> n.addAndGet(20000000);
        var result = run(d, a, n, new AtomicLong(epoch), op -> Mono.just("private-value"));
        assertEquals(1, d.commits);
        assertEquals("private-value", GlNativeTimeWindowTest.call(result, "value", new Class[] {}));
        var op = GlNativeTimeWindowTest.call(result, "operation", new Class[] {});
        assertThrows(
                RuntimeException.class,
                () -> GlNativeTimeWindowTest.call(op, "requireCurrent", new Class[] {}));
    }

    @Test
    void failedCommitIsUncertainAndDoesNotEmitPrivateValue() {
        var a = GlNativeContextTest.admission();
        var d = GlNativeContextTest.driver(a);
        d.failCommit = true;
        var error =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                run(
                                        d,
                                        a,
                                        new AtomicLong(System.nanoTime()),
                                        new AtomicLong(System.currentTimeMillis()),
                                        op -> Mono.just("private-value")));
        assertTrue(error.getClass().getSimpleName().contains("CompletionUncertain"));
        assertFalse(error.toString().contains("private-value"));
    }

    @Test
    void workFailurePropagatesAfterRollbackWithoutInventingCommit() {
        var a = GlNativeContextTest.admission();
        var d = GlNativeContextTest.driver(a);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        run(
                                d,
                                a,
                                new AtomicLong(System.nanoTime()),
                                new AtomicLong(System.currentTimeMillis()),
                                op ->
                                        Mono.error(
                                                new IllegalArgumentException(
                                                        "synthetic work failure"))));
        assertEquals(0, d.commits);
        assertEquals(1, d.rollbacks);
    }
    @Test void failedRollbackDoesNotMasqueradeAsAnOrdinaryRejectedWrite(){var a=GlNativeContextTest.admission();var d=GlNativeContextTest.driver(a);d.failRollback=true;var error=assertThrows(RuntimeException.class,()->run(d,a,new AtomicLong(System.nanoTime()),new AtomicLong(System.currentTimeMillis()),op->Mono.error(new IllegalArgumentException("synthetic work failure"))));assertTrue(error.getClass().getSimpleName().contains("CompletionUncertain"));assertEquals(0,d.commits);assertEquals(1,d.rollbacks);}

}
