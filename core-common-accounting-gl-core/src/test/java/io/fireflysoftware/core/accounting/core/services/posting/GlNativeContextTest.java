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

import io.r2dbc.spi.*;

import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

class GlNativeContextTest {
    static class Driver implements ConnectionFactory {
        final List<String> sql = new ArrayList<>();
        final Map<String, Object> nativeRow = new HashMap<>();
        Runnable onGuard = () -> {}, onCommit = () -> {};
        boolean failCommit, failRollback, stallCommit, stallGuard, stallRollback;
        int commitCancelled, guardCancelled, rollbackCancelled;
        int commits, rollbacks;
        Connection connection =
                (Connection)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {Connection.class},
                                (p, m, a) -> {
                                    if (m.getName().equals("createStatement"))
                                        return statement((String) a[0]);
                                    if (m.getName().equals("isAutoCommit")) return true;
                                    if (m.getName().equals("commitTransaction")) {
                                        commits++;
                                        onCommit.run();
                                        if (stallCommit)
                                            return Mono.<Void>never()
                                                    .doOnCancel(() -> commitCancelled++);
                                        return failCommit
                                                ? Mono.error(
                                                        new IllegalStateException(
                                                                "synthetic commit failure"))
                                                : Mono.empty();
                                    }
                                    if (m.getName().equals("rollbackTransaction")) {
                                        rollbacks++;
                                        if (stallRollback)
                                            return Mono.<Void>never()
                                                    .doOnCancel(() -> rollbackCancelled++);
                                        return failRollback
                                                ? Mono.error(
                                                        new IllegalStateException(
                                                                "synthetic rollback failure"))
                                                : Mono.empty();
                                    }
                                    return Mono.empty();
                                });

        Statement statement(String query) {
            sql.add(query);
            return (Statement)
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class[] {Statement.class},
                            (p, m, a) -> {
                                if (m.getName().equals("execute")) {
                                    if (stallGuard && query.startsWith("WITH protected"))
                                        return Mono.<Result>never()
                                                .doOnCancel(() -> guardCancelled++);
                                    if (query.startsWith("WITH protected")) onGuard.run();
                                    return Mono.just(
                                            new Result() {
                                                public Publisher<Long> getRowsUpdated() {
                                                    return Mono.just(1L);
                                                }

                                                public <T> Publisher<T> map(
                                                        BiFunction<Row, RowMetadata, ? extends T>
                                                                f) {
                                                    Row row =
                                                            (Row)
                                                                    Proxy.newProxyInstance(
                                                                            getClass()
                                                                                    .getClassLoader(),
                                                                            new Class[] {Row.class},
                                                                            (rp, rm, ra) ->
                                                                                    query
                                                                                                    .startsWith(
                                                                                                            "WITH protected")
                                                                                            ? Boolean
                                                                                                    .TRUE
                                                                                            : nativeRow
                                                                                                    .get(
                                                                                                            (String)
                                                                                                                    ra[
                                                                                                                            0]));
                                                    return Mono.just(f.apply(row, null));
                                                }

                                                public <T> Publisher<T> map(
                                                        Function<
                                                                        ? super
                                                                                io.r2dbc.spi
                                                                                        .Readable,
                                                                        ? extends T>
                                                                f) {
                                                    return Flux.empty();
                                                }

                                                public Result filter(Predicate<Segment> f) {
                                                    return this;
                                                }

                                                public <T> Publisher<T> flatMap(
                                                        Function<
                                                                        Segment,
                                                                        ? extends
                                                                                Publisher<
                                                                                        ? extends
                                                                                                T>>
                                                                f) {
                                                    return Flux.empty();
                                                }
                                            });
                                }
                                if (m.getReturnType() == Statement.class) return p;
                                throw new UnsupportedOperationException(m.getName());
                            });
        }

        public Publisher<? extends Connection> create() {
            return Mono.just(connection);
        }

        public ConnectionFactoryMetadata getMetadata() {
            return () -> "PostgreSQL";
        }
    }

    static GlAccountingAuthority.Admission admission() {
        var tenant = GlPostingApplicationServiceTest.TENANT.toString();
        var book = GlPostingApplicationServiceTest.BOOK.toString();
        var action = GlPostingApplicationServiceTest.ACTION.toString();
        var canonical =
                GlPostingRules.canonical(
                        "GL_READ_CONTEXT_V1", List.of(tenant, book, "read", action));
        var intent =
                new AccountingApprovalIntent(
                        tenant,
                        book,
                        "read",
                        GlPostingRules.digest(canonical),
                        canonical,
                        Map.of("actionId", action));
        var actor =
                SecurityPrincipal.builder()
                        .subject("synthetic-reader")
                        .issuer("https://synthetic-reader.invalid")
                        .claims(Map.of("exp", Instant.now().plusSeconds(120)))
                        .build();
        return new GlAccountingAuthority.Admission(
                actor, null, Instant.now().plusSeconds(60).toEpochMilli(), intent, Set.of());
    }

    static Driver driver(GlAccountingAuthority.Admission a) {
        var d = new Driver();
        d.nativeRow.putAll(
                Map.ofEntries(
                        Map.entry("db_now_ms", System.currentTimeMillis()),
                        Map.entry("effective_until_ms", System.currentTimeMillis() + 10000),
                        Map.entry("tenant_id", a.intent().tenantId()),
                        Map.entry("book_id", a.intent().bookId()),
                        Map.entry("operation", a.intent().operation()),
                        Map.entry("action_id", a.intent().exactContext().get("actionId")),
                        Map.entry("posting_id", ""),
                        Map.entry("intent_digest", a.intent().intentDigest()),
                        Map.entry("account_ids", "[]"),
                        Map.entry("actor_issuer", a.actor().issuer()),
                        Map.entry("actor_subject", a.actor().subject()),
                        Map.entry("transaction_id", "12345")));
        return d;
    }

    static Object observe(Driver d, GlAccountingAuthority.Admission a) {
        var context = new GlAccountingDatabaseContext(DatabaseClient.create(d));
        Mono<?> query =
                assertDoesNotThrow(
                        () ->
                                (Mono<?>)
                                        context.getClass()
                                                .getDeclaredMethod(
                                                        "establishWithNativeTime",
                                                        GlAccountingAuthority.Admission.class)
                                                .invoke(context, a));
        return TransactionalOperator.create(new R2dbcTransactionManager(d))
                .transactional(query)
                .block();
    }

    @Test
    void actualNativeBoundIsReturnedInsteadOfAdmittedHorizon() {
        var a = admission();
        var d = driver(a);
        var value = observe(d, a);
        assertEquals(
                d.nativeRow.get("effective_until_ms"),
                GlNativeTimeWindowTest.call(value, "effectiveUntilEpochMillis", new Class[] {}));
        assertTrue(d.sql.getLast().contains("MATERIALIZED"));
        assertTrue(d.sql.getLast().contains("FROM installed"));
    }

    @Test
    void nativeScopeMismatchIsRefused() {
        var a = admission();
        var d = driver(a);
        d.nativeRow.put("book_id", GlPostingApplicationServiceTest.id(99).toString());
        assertThrows(RuntimeException.class, () -> observe(d, a));
    }

    @Test
    void nativeBoundCannotBeReplacedByAWiderCallerBound() {
        var a = admission();
        var d = driver(a);
        d.nativeRow.put("effective_until_ms", a.validUntilEpochMillis() + 1);
        assertThrows(RuntimeException.class, () -> observe(d, a));
    }

    @Test
    void missingNativeTimingIsUnavailable() {
        var a = admission();
        var d = driver(a);
        d.nativeRow.remove("db_now_ms");
        assertThrows(RuntimeException.class, () -> observe(d, a));
    }

    @Test
    void legacyContextSignatureRemainsUsableWithoutNativeTimingClaim() {
        var a = admission();
        var d = driver(a);
        d.nativeRow.clear();
        var context = new GlAccountingDatabaseContext(DatabaseClient.create(d));
        assertNull(
                TransactionalOperator.create(new R2dbcTransactionManager(d))
                        .transactional(context.establish(a))
                        .block());
    }
}
