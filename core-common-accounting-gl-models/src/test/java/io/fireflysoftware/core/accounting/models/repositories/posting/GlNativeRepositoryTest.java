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

import static org.junit.jupiter.api.Assertions.*;

import io.fireflysoftware.core.accounting.models.entities.posting.*;
import io.r2dbc.spi.*;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.r2dbc.core.DatabaseClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.*;

public class GlNativeRepositoryTest {
    static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID BOOK = UUID.fromString("00000000-0000-4000-8000-000000000002");

    public static class Driver implements ConnectionFactory {
        public final List<String> sql = new ArrayList<>();
        public long rows = 1;
        public int commits, rollbacks;
        public boolean failCommit;
        public boolean contextAllowed;
        public Connection connection =
                (Connection)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {Connection.class},
                                (p, m, a) -> {
                                    switch (m.getName()) {
                                        case "createStatement":
                                            return statement((String) a[0]);
                                        case "getMetadata":
                                            return new ConnectionMetadata() {
                                                public String getDatabaseProductName() {
                                                    return "PostgreSQL";
                                                }

                                                public String getDatabaseVersion() {
                                                    return "17";
                                                }
                                            };
                                        case "isAutoCommit":
                                            return true;
                                        case "validate":
                                            return Mono.just(true);
                                        case "commitTransaction":
                                            commits++;
                                            return failCommit
                                                    ? Mono.error(
                                                            new IllegalStateException(
                                                                    "synthetic commit failure"))
                                                    : Mono.empty();
                                        case "rollbackTransaction":
                                            rollbacks++;
                                            return Mono.empty();
                                        case "beginTransaction",
                                        "close",
                                        "setAutoCommit",
                                        "setTransactionIsolationLevel",
                                        "setLockWaitTimeout",
                                        "setStatementTimeout":
                                            return Mono.empty();
                                        case "toString":
                                            return "NetworklessSourceDriver";
                                        default:
                                            throw new UnsupportedOperationException(m.getName());
                                    }
                                });

        Statement statement(String query) {
            sql.add(query);
            return (Statement)
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class[] {Statement.class},
                            (p, m, a) -> {
                                if (m.getName().equals("execute"))
                                    return Mono.just(
                                            new Result() {
                                                public Publisher<Long> getRowsUpdated() {
                                                    return Mono.just(rows);
                                                }

                                                public <T> Publisher<T> map(
                                                        BiFunction<Row, RowMetadata, ? extends T>
                                                                f) {
                                                    if (!contextAllowed
                                                            || !query.startsWith("WITH protected"))
                                                        return Flux.empty();
                                                    Row row =
                                                            (Row)
                                                                    Proxy.newProxyInstance(
                                                                            getClass()
                                                                                    .getClassLoader(),
                                                                            new Class[] {Row.class},
                                                                            (p, m, a) ->
                                                                                    m.getName()
                                                                                                    .equals(
                                                                                                            "get")
                                                                                            ? Boolean
                                                                                                    .TRUE
                                                                                            : null);
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

        public R2dbcEntityTemplate template() {
            return new R2dbcEntityTemplate(DatabaseClient.create(this), PostgresDialect.INSTANCE);
        }
    }

    Object repository(Driver driver) {
        return assertDoesNotThrow(
                () ->
                        Class.forName(
                                        "io.fireflysoftware.core.accounting.models.repositories.posting.impl.GlNativePostingRepositoryImpl")
                                .getConstructor(R2dbcEntityTemplate.class)
                                .newInstance(driver.template()));
    }

    Mono<?> call(Object repo, String name, Class<?>[] types, Object... args) {
        return assertDoesNotThrow(
                () -> (Mono<?>) repo.getClass().getMethod(name, types).invoke(repo, args));
    }

    @Test
    void actionInsertCarriesOriginalV8RequestAndOmitsGeneratedColumns() {
        var d = new Driver();
        var row =
                new GlNativeJournalAction(
                        BOOK,
                        TENANT,
                        BOOK,
                        "POST",
                        "a".repeat(64),
                        "canonical",
                        "b".repeat(64),
                        "economic",
                        "c".repeat(64),
                        new UUID[] {BOOK},
                        1000L,
                        BOOK,
                        "https://synthetic.invalid",
                        "synthetic-actor",
                        1000L,
                        BOOK,
                        "GL_POSTING_REQUEST_V1",
                        "{}",
                        java.time.Instant.parse("2026-10-26T12:34:56.123Z"));
        call(repository(d), "appendAction", new Class[] {GlNativeJournalAction.class}, row).block();
        var sql = d.sql.getFirst();
        assertTrue(sql.contains("request_shape"));
        assertTrue(sql.contains("original_request"));
        assertTrue(sql.contains("account_ids"));
        for (var generated : new String[] {"request_document", "owner_xid", "recorded_at"})
            assertFalse(sql.contains(generated));
        var copy = row.accountIds();
        copy[0] = TENANT;
        assertEquals(BOOK, row.accountIds()[0]);
    }

    @Test
    void allControlReadsStayScopedWithoutApplicationLockQueries() {
        for (var name :
                new String[] {
                    "findBook", "findPeriod", "findAccount", "findCurrency", "findMapping"
                }) {
            var d = new Driver();
            var r = repository(d);
            if (name.equals("findBook"))
                call(r, name, new Class[] {UUID.class, UUID.class}, TENANT, BOOK).block();
            else
                call(r, name, new Class[] {UUID.class, UUID.class, UUID.class}, TENANT, BOOK, BOOK)
                        .block();
            var sql = d.sql.getFirst();
            assertTrue(sql.startsWith("SELECT"));
            assertTrue(sql.contains("tenant_id"));
            assertTrue(sql.contains("WHERE"));
            assertFalse(sql.contains("FOR UPDATE"));
            assertFalse(sql.contains("FOR NO KEY UPDATE"));
            if (!name.equals("findBook")) assertTrue(sql.contains("book_id"));
        }
    }

    @Test
    void assignedCompositeEntryIsInsertedWithAllIdColumns() {
        var d = new Driver();
        var r = repository(d);
        var row =
                new GlNativeEntry(
                        new GlNativeEntry.Key(BOOK, "entry"), TENANT, BOOK, BOOK, "Entry");
        assertEquals(row, call(r, "appendEntry", new Class[] {GlNativeEntry.class}, row).block());
        var sql = d.sql.getFirst();
        assertTrue(sql.startsWith("INSERT INTO \"public\".\"gl_native_entry\""), sql);
        assertTrue(sql.contains("posting_id"), sql);
        assertTrue(sql.contains("entry_key"), sql);
        assertFalse(sql.contains("(id,"));
    }

    @Test
    void lineInsertNeverWritesGeneratedAmountValue() {
        var d = new Driver();
        var row =
                new GlNativeLine(
                        new GlNativeLine.Key(BOOK, "entry", "line"),
                        TENANT,
                        BOOK,
                        BOOK,
                        BOOK,
                        1,
                        BOOK,
                        1,
                        2,
                        "a".repeat(64),
                        "DR",
                        "100",
                        "Synthetic line");
        call(repository(d), "appendLine", new Class[] {GlNativeLine.class}, row).block();
        assertTrue(d.sql.getFirst().contains("line_key"));
        assertTrue(d.sql.getFirst().contains("amount_minor"));
        assertFalse(d.sql.getFirst().contains("amount_value"));
    }

    @Test
    void eventInsertUsesActionAndChannelCompositeKey() {
        var d = new Driver();
        var row =
                new GlNativeJournalEvent(
                        new GlNativeJournalEvent.Key(BOOK, "AUDIT"),
                        TENANT,
                        BOOK,
                        "GL_ACCOUNTING_EVENT_V1",
                        "{}",
                        "a".repeat(64));
        call(repository(d), "appendEvent", new Class[] {GlNativeJournalEvent.class}, row).block();
        assertTrue(d.sql.getFirst().contains("action_id"));
        assertTrue(d.sql.getFirst().contains("channel"));
        assertTrue(d.sql.getFirst().startsWith("INSERT"));
    }

    @Test
    void sequenceCasIsScopedAndChecksExactAffectedCount() {
        var d = new Driver();
        var r = repository(d);
        assertEquals(
                6L,
                call(
                                r,
                                "advanceSequence",
                                new Class[] {UUID.class, UUID.class, long.class},
                                TENANT,
                                BOOK,
                                5L)
                        .block());
        var sql = d.sql.getFirst();
        assertTrue(
                sql.startsWith(
                        "UPDATE \"public\".\"gl_native_book_sequence\" SET \"last_sequence\""),
                sql);
        assertTrue(sql.contains("tenant_id"));
        assertTrue(sql.contains("book_id"));
        assertTrue(sql.contains("WHERE"));
        assertTrue(sql.substring(sql.indexOf("WHERE")).contains("last_sequence"));
        for (long count : new long[] {0, 2}) {
            d.rows = count;
            assertThrows(
                    RuntimeException.class,
                    () ->
                            call(
                                            r,
                                            "advanceSequence",
                                            new Class[] {UUID.class, UUID.class, long.class},
                                            TENANT,
                                            BOOK,
                                            5L)
                                    .block());
        }
    }

    @Test
    void exhaustedSequenceDoesNotIssueAnyWrite() {
        var d = new Driver();
        var r = repository(d);
        assertThrows(
                RuntimeException.class,
                () ->
                        call(
                                        r,
                                        "advanceSequence",
                                        new Class[] {UUID.class, UUID.class, long.class},
                                        TENANT,
                                        BOOK,
                                        Long.MAX_VALUE)
                                .block());
        assertTrue(d.sql.isEmpty());
    }

    @Test
    void sequenceReadUsesBothScopePredicates() {
        var d = new Driver();
        call(repository(d), "findSequence", new Class[] {UUID.class, UUID.class}, TENANT, BOOK)
                .block();
        assertTrue(d.sql.getFirst().contains("tenant_id"));
        assertTrue(d.sql.getFirst().contains("book_id"));
        assertTrue(d.sql.getFirst().contains("WHERE"));
    }
}
