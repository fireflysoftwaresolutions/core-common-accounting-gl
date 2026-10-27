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

package io.fireflysoftware.core.accounting.sdk;

import static io.fireflysoftware.core.accounting.sdk.GlAccountingScopedRlsRuntimeIT.*;
import static io.fireflysoftware.core.accounting.sdk.GlNativeAccountingSchemaIT.execute;
import static io.fireflysoftware.core.accounting.sdk.GlNativeJournalAtomicSchemaIT.*;

import static org.assertj.core.api.Assertions.*;

import io.fireflysoftware.core.accounting.core.services.posting.GlAccountingAuthority;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import reactor.core.publisher.Mono;

import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

class GlNativeAdmissionCutoffIT {
    @BeforeAll
    static void prepare() throws Exception {
        dbOwner.start();
        setup();
    }

    @AfterAll
    static void cleanup() {
        close();
        dbOwner.stop();
    }

    static long databaseNow(Connection c) throws Exception {
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT floor(extract(epoch FROM"
                                    + " clock_timestamp())*1000)::bigint")) {
            assertThat(r.next()).isTrue();
            return r.getLong(1);
        }
    }

    static Effect withDeadline(Fixture f, long deadline) throws Exception {
        var base = scopedEffect(f, false);
        var changed = changedOriginal(f, base, body -> body.put("notAfterEpochMillis", deadline));
        return new Effect(
                changed.action(),
                changed.posting(),
                changed.receipt(),
                changed.sourceKey(),
                changed.canonical(),
                deadline,
                changed.originalRequest());
    }

    static void waitPastDatabaseDeadline(Connection c, long deadline) throws Exception {
        try (var s =
                c.prepareStatement(
                        "SELECT pg_sleep(greatest(0,(?-floor(extract(epoch FROM"
                            + " clock_timestamp())*1000))/1000.0)+0.025)")) {
            s.setLong(1, deadline);
            s.execute();
        }
        assertThat(databaseNow(c)).isGreaterThanOrEqualTo(deadline);
    }

    static void noEffects(Fixture f) throws Exception {
        try (var c = ownerConnection()) {
            for (var table :
                    List.of(
                            "gl_native_journal_action",
                            "gl_native_posting",
                            "gl_native_entry",
                            "gl_native_line",
                            "gl_native_journal_event")) {
                assertThat(count(c, table, f.scope().book())).as(table).isZero();
            }
        }
    }

    static void rejectedAdmission(CompletableFuture<?> future) {
        assertThatThrownBy(() -> future.get(6, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(
                        error -> {
                            Throwable cause = error;
                            while (cause.getCause() != null) cause = cause.getCause();
                            assertThat(cause).isInstanceOf(io.r2dbc.spi.R2dbcException.class);
                            assertThat(((io.r2dbc.spi.R2dbcException) cause).getSqlState())
                                    .as("Native admission error: %s", cause.getMessage())
                                    .isEqualTo("23514");
                            assertThat(cause.getMessage()).contains("GL_NATIVE_");
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"book", "period", "account", "currency"})
    void firstAdmissionAfterAnActualFinancialGateWaitCannotCrossTheRequestCutoff(String gate)
            throws Exception {
        waitAtGate(gate, false);
    }

    @Test
    void authorityIsRecheckedAfterTheActualAdmissionWait() throws Exception {
        waitAtGate("book", true);
    }

    static void waitAtGate(String gate, boolean expireAuthority) throws Exception {
        var f = emptyFixture();
        try (var blocker = ownerConnection()) {
            long cutoff = databaseNow(blocker) + 1500;
            var effect = withDeadline(f, expireAuthority ? cutoff + 60000 : cutoff);
            var original = write(f, effect, "post");
            var admitted =
                    expireAuthority
                            ? new GlAccountingAuthority.Admission(
                                    original.actor(),
                                    original.approval(),
                                    cutoff,
                                    original.intent(),
                                    original.accountIds())
                            : original;
            var commands = captured(c -> complete(c, f, effect, 1, null, "12500"));
            String table =
                    switch (gate) {
                        case "book" -> "gl_native_book";
                        case "period" -> "gl_native_period";
                        case "account" -> "gl_native_account";
                        case "currency" -> "gl_native_currency_admission";
                        default -> throw new IllegalArgumentException("Synthetic test gate");
                    };
            UUID id =
                    switch (gate) {
                        case "book" -> f.scope().book();
                        case "period" -> f.period();
                        case "account" -> f.debit();
                        default -> f.scope().currency();
                    };
            blocker.setAutoCommit(false);
            execute(
                    blocker,
                    "SELECT "
                            + (gate.equals("account") ? "account_id" : "id")
                            + " FROM "
                            + table
                            + " WHERE "
                            + (gate.equals("account") ? "account_id" : "id")
                            + "='"
                            + id
                            + "' FOR NO KEY UPDATE");
            String app = "synthetic-gl-cutoff-" + UUID.randomUUID();
            var future =
                    CompletableFuture.supplyAsync(
                            () ->
                                    transactions
                                            .execute(
                                                    admitted,
                                                    () ->
                                                            db.sql(
                                                                            "SET LOCAL"
                                                                                + " application_name='"
                                                                                    + app
                                                                                    + "'")
                                                                    .fetch()
                                                                    .rowsUpdated()
                                                                    .then(
                                                                            executeCommands(
                                                                                    commands,
                                                                                    admitted)))
                                            .block(Duration.ofSeconds(10)));
            boolean waited = false;
            long limit = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            try {
                while (System.nanoTime() < limit) {
                    try (var observer = ownerConnection();
                            var s =
                                    observer.prepareStatement(
                                            "SELECT exists(SELECT 1 FROM pg_stat_activity WHERE"
                                                + " application_name=? AND"
                                                + " wait_event_type='Lock')")) {
                        s.setString(1, app);
                        try (var r = s.executeQuery()) {
                            r.next();
                            if (r.getBoolean(1)) {
                                waited = true;
                                break;
                            }
                        }
                    }
                    if (future.isDone()) break;
                    Thread.sleep(10);
                }
                assertThat(waited).as("real PostgreSQL " + gate + " admission gate wait").isTrue();
                waitPastDatabaseDeadline(blocker, cutoff);
                blocker.commit();
                rejectedAdmission(future);
            } finally {
                blocker.rollback();
                try {
                    future.get(6, TimeUnit.SECONDS);
                } catch (ExecutionException ignored) {
                }
            }
        }
        noEffects(f);
    }

    @Test
    void admittedBeforeTheCutoffCanFinishItsEconomicRowsAndCommitAfterTheCutoff() throws Exception {
        var f = emptyFixture();
        try (var observer = ownerConnection()) {
            long deadline = databaseNow(observer) + 1500;
            var effect = withDeadline(f, deadline);
            var admitted = write(f, effect, "post");
            var commands = captured(c -> complete(c, f, effect, 1, null, "12500"));
            var admittedBeforeDeadline = new CountDownLatch(1);
            var finish = new CompletableFuture<Void>();
            var future =
                    CompletableFuture.supplyAsync(
                            () ->
                                    transactions
                                            .execute(
                                                    admitted,
                                                    () ->
                                                            executeCommands(
                                                                            commands.subList(0, 1),
                                                                            admitted)
                                                                    .then(
                                                                            Mono.fromRunnable(
                                                                                    admittedBeforeDeadline
                                                                                            ::countDown))
                                                                    .then(Mono.fromFuture(finish))
                                                                    .then(
                                                                            executeCommands(
                                                                                    commands
                                                                                            .subList(
                                                                                                    1,
                                                                                                    commands
                                                                                                            .size()),
                                                                                    admitted)))
                                            .block(Duration.ofSeconds(10)));
            try {
                assertThat(admittedBeforeDeadline.await(4, TimeUnit.SECONDS)).isTrue();
                assertThat(databaseNow(observer)).isLessThan(deadline);
                waitPastDatabaseDeadline(observer, deadline);
            } finally {
                finish.complete(null);
            }
            future.get(6, TimeUnit.SECONDS);
            assertThat(count(observer, "gl_native_line", f.scope().book())).isEqualTo(2);
            assertThat(count(observer, "gl_native_journal_event", f.scope().book())).isEqualTo(2);
        }
    }

    @Test
    void completedAdmissionStatementAlreadyHoldsEveryFinancialGate() throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, false);
        var admitted = write(f, effect, "post");
        var commands = captured(c -> complete(c, f, effect, 1, null, "12500"));
        var actionReturned = new CountDownLatch(1);
        var finish = new CompletableFuture<Void>();
        var future = CompletableFuture.supplyAsync(() -> transactions.execute(admitted,
                () -> executeCommands(commands.subList(0, 1), admitted)
                        .then(Mono.fromRunnable(actionReturned::countDown))
                        .then(Mono.fromFuture(finish))
                        .then(executeCommands(commands.subList(1, commands.size()), admitted)))
                .block(Duration.ofSeconds(10)));
        try (var observer = ownerConnection()) {
            try {
                assertThat(actionReturned.await(4, TimeUnit.SECONDS)).isTrue();
                var locks = Map.of("gl_native_book:id", List.of(f.scope().book()),
                        "gl_native_period:id", List.of(f.period()),
                        "gl_native_account:account_id", List.of(f.debit(), f.credit()),
                        "gl_native_currency_admission:id", List.of(f.scope().currency()));
                for (var lock : locks.entrySet()) {
                    var target = lock.getKey().split(":");
                    for (var id : lock.getValue()) {
                        assertThatThrownBy(() -> execute(observer, "SELECT " + target[1]
                                + " FROM " + target[0] + " WHERE " + target[1] + "='" + id
                                + "' FOR NO KEY UPDATE NOWAIT"))
                                .isInstanceOfSatisfying(java.sql.SQLException.class,
                                        error -> assertThat(error.getSQLState()).isEqualTo("55P03"));
                    }
                }
            } finally { finish.complete(null); }
            future.get(6, TimeUnit.SECONDS);
            assertThat(count(observer, "gl_native_line", f.scope().book())).isEqualTo(2);
        }
    }

    @Test
    void evenBootstrapOwnershipDoesNotSubstituteForTheExactNativeActionContext() throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, false);
        try (var c = ownerConnection()) {
            c.setAutoCommit(false);
            assertThatThrownBy(() -> action(c, f, effect, "POST"))
                    .isInstanceOfSatisfying(
                            java.sql.SQLException.class,
                            error -> {
                                assertThat(error.getSQLState()).isEqualTo("23514");
                                assertThat(error.getMessage()).contains("GL_NATIVE_ADMISSION:");
                            });
            c.rollback();
        }
        noEffects(f);
    }
}
