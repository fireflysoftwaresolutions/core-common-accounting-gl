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

import static io.fireflysoftware.core.accounting.sdk.GlNativeAccountingSchemaIT.*;
import static io.fireflysoftware.core.accounting.sdk.GlNativeJournalAtomicSchemaIT.*;

import static org.assertj.core.api.Assertions.*;

import io.fireflysoftware.core.accounting.core.services.posting.v1.*;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.v1.*;
import io.r2dbc.pool.*;
import io.r2dbc.postgresql.*;

import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.*;
import java.time.*;
import java.util.*;

@Testcontainers
class GlAccountingScopedRlsRuntimeIT {
    @Container
    static PostgreSQLContainer dbOwner =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("synthetic_gl_scoped_rls")
                    .withUsername("gl_migration")
                    .withPassword("synthetic-only")
                    .withCreateContainerCmdModifier(
                            c ->
                                    c.getHostConfig()
                                            .withPortBindings(
                                                    new com.github.dockerjava.api.model.PortBinding(
                                                            com.github.dockerjava.api.model.Ports
                                                                    .Binding.bindIp("127.0.0.1"),
                                                            new com.github.dockerjava.api.model
                                                                    .ExposedPort(5432))));

    static ConnectionPool pool;
    static DatabaseClient db;
    static GlAccountingTransaction transactions;

    @BeforeAll
    static void setup() throws Exception {
        Flyway.configure()
                .dataSource(dbOwner.getJdbcUrl(), dbOwner.getUsername(), dbOwner.getPassword())
                .locations(System.getProperty("gl.migration.location", "classpath:db/migration"))
                .load()
                .migrate();
        try (var c = ownerConnection()) {
            execute(
                    c,
                    "CREATE ROLE gl_scoped_app LOGIN PASSWORD 'synthetic-only' NOSUPERUSER"
                        + " NOBYPASSRLS NOCREATEROLE NOCREATEDB;REVOKE CREATE ON SCHEMA public FROM"
                        + " PUBLIC;GRANT USAGE ON SCHEMA public TO gl_scoped_app;GRANT"
                        + " SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO"
                        + " gl_scoped_app");
        }
        pool =
                new ConnectionPool(
                        ConnectionPoolConfiguration.builder(
                                        new PostgresqlConnectionFactory(
                                                PostgresqlConnectionConfiguration.builder()
                                                        .host(dbOwner.getHost())
                                                        .port(dbOwner.getMappedPort(5432))
                                                        .database(dbOwner.getDatabaseName())
                                                        .username("gl_scoped_app")
                                                        .password("synthetic-only")
                                                        .build()))
                                .initialSize(0)
                                .maxSize(2)
                                .build());
        db = DatabaseClient.create(pool);
        transactions =
                new GlAccountingTransaction(
                        new R2dbcTransactionManager(pool), new GlAccountingDatabaseContext(db));
    }

    @AfterAll
    static void close() {
        if (pool != null) pool.dispose();
    }

    static Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(
                dbOwner.getJdbcUrl(), dbOwner.getUsername(), dbOwner.getPassword());
    }

    record Data(Fixture fixture, Effect effect) {}

    static Data data() throws Exception {
        var fixture = emptyFixture();
        var effect = scopedEffect(fixture, false);
        var admitted = write(fixture, effect, "post");
        transactions
                .execute(
                        admitted,
                        () -> executeCommands(capturedUnchecked(fixture, effect), admitted))
                .block();
        return new Data(fixture, effect);
    }

    static GlAccountingAuthority authority(Fixture f) {
        var beans = new StaticListableBeanFactory();
        beans.addBean(
                "actor",
                (SecurityContextPort)
                        () ->
                                Mono.just(
                                        SecurityPrincipal.builder()
                                                .subject("synthetic-scoped-gl-operator")
                                                .issuer("https://synthetic-scoped-gl.invalid")
                                                .claims(
                                                        Map.of(
                                                                "exp",
                                                                Instant.now().plusSeconds(60)))
                                                .build()));
        var tenant = f.scope().tenant().toString();
        var book = f.scope().book().toString();
        beans.addBean(
                "policy",
                (PolicyDecisionPort)
                        (actor, action, resource, context) ->
                                Mono.just(
                                        tenant.equals(context.get("tenantId"))
                                                        && book.equals(context.get("bookId"))
                                                        && Set.of(
                                                                        "accounting/book/" + book,
                                                                        "accounting/account/"
                                                                                + f.debit(),
                                                                        "accounting/account/"
                                                                                + f.credit())
                                                                .contains(resource)
                                                ? Decision.permit()
                                                : Decision.deny("Synthetic exact fixture denied")));
        beans.addBean(
                "approval",
                (AccountingApprovalPort)
                        (actor, intent) ->
                                Mono.just(
                                        new AccountingApproval(
                                                tenant,
                                                book,
                                                intent.operation(),
                                                intent.intentDigest(),
                                                actor.issuer(),
                                                actor.subject(),
                                                "https://synthetic-scoped-gl-approval.invalid",
                                                "synthetic-scoped-approval",
                                                "b".repeat(64),
                                                Instant.now().plusSeconds(45).toEpochMilli())));
        return new GlAccountingAuthority(
                beans.getBeanProvider(SecurityContextPort.class),
                beans.getBeanProvider(PolicyDecisionPort.class),
                beans.getBeanProvider(AccountingApprovalPort.class),
                beans.getBeanProvider(AccountingBookOwnerPort.class),
                new MockEnvironment()
                        .withProperty("firefly.security.resource-server.strict-policy", "true")
                        .withProperty(
                                "firefly.accounting.approval.trusted-issuers",
                                "https://synthetic-scoped-gl-approval.invalid"));
    }

    static GlAccountingAuthority.Admission read(
            Data data, Collection<String> accounts, String operation) {
        var f = data.fixture();
        var posting = data.effect().posting().toString();
        var canonical =
                GlPostingRules.canonical(
                        "GL_READ_CONTEXT_V1",
                        List.of(
                                f.scope().tenant().toString(),
                                f.scope().book().toString(),
                                operation,
                                posting));
        var intent =
                new AccountingApprovalIntent(
                        f.scope().tenant().toString(),
                        f.scope().book().toString(),
                        operation,
                        GlPostingRules.digest(canonical),
                        canonical,
                        Map.of("postingId", posting));
        return (accounts.isEmpty()
                        ? authority(f).authorize(intent, false)
                        : authority(f).authorizeAccounts(intent, accounts, false))
                .block(Duration.ofSeconds(5));
    }

    static Mono<Long> rows(String table) {
        return db.sql("SELECT count(*) AS n FROM public." + table)
                .map((r, m) -> r.get("n", Long.class))
                .one();
    }

    @Test
    void accountAndBookScopesAreExactAndPoolReuseDoesNotRetainVisibility() throws Exception {
        var data = data();
        data();
        var f = data.fixture();
        var admitted = read(data, List.of(f.debit().toString()), "read");
        var actual =
                transactions
                        .execute(
                                admitted,
                                () -> Mono.zip(rows("gl_native_book"), rows("gl_native_account")))
                        .block(Duration.ofSeconds(5));
        assertThat(actual.getT1()).isEqualTo(1);
        assertThat(actual.getT2()).isEqualTo(1);
        assertThat(rows("gl_native_book").block()).isZero();
        assertThat(rows("gl_native_account").block()).isZero();
    }

    @Test
    void wholeReceiptHistoryRequiresEveryCurrentPostingAccountGrant() throws Exception {
        var data = data();
        var f = data.fixture();
        var one = read(data, List.of(f.debit().toString()), "history");
        var hidden =
                transactions
                        .execute(
                                one,
                                () ->
                                        Mono.zip(
                                                rows("gl_native_journal_action"),
                                                        rows("gl_native_posting"),
                                                rows("gl_native_entry"),
                                                        rows("gl_native_journal_event")))
                        .block();
        assertThat(hidden.getT1()).isZero();
        assertThat(hidden.getT2()).isZero();
        assertThat(hidden.getT3()).isZero();
        assertThat(hidden.getT4()).isZero();
        var both = read(data, List.of(f.debit().toString(), f.credit().toString()), "history");
        var visible =
                transactions
                        .execute(
                                both,
                                () ->
                                        Mono.zip(
                                                rows("gl_native_journal_action"),
                                                rows("gl_native_posting"),
                                                rows("gl_native_entry"),
                                                rows("gl_native_journal_event")))
                        .block();
        assertThat(visible.getT1()).isEqualTo(1);
        assertThat(visible.getT2()).isEqualTo(1);
        assertThat(visible.getT3()).isEqualTo(1);
        assertThat(visible.getT4()).isEqualTo(2);
    }

    @Test
    void explicitDiscoveryReturnsOnlyExactPostingAccountIdsAndNoFinancialRows() throws Exception {
        var data = data();
        var other = data();
        var f = data.fixture();
        var admitted = read(data, List.of(), "read.resources");
        var ids =
                transactions
                        .execute(
                                admitted,
                                () ->
                                        db.sql(
                                                        "SELECT account_id FROM"
                                                            + " public.gl_native_discover_posting_accounts(:tenant,:book,:posting)")
                                                .bind("tenant", f.scope().tenant())
                                                .bind("book", f.scope().book())
                                                .bind("posting", data.effect().posting())
                                                .map(
                                                        (r, m) ->
                                                                r.get("account_id", UUID.class)
                                                                        .toString())
                                                .all()
                                                .collectList())
                        .block();
        assertThat(ids).containsExactlyInAnyOrder(f.debit().toString(), f.credit().toString());
        var noDetails =
                transactions
                        .execute(
                                admitted,
                                () ->
                                        Mono.zip(
                                                rows("gl_native_journal_action"),
                                                rows("gl_native_posting"),
                                                rows("gl_native_line")))
                        .block();
        assertThat(noDetails.getT1()).isZero();
        assertThat(noDetails.getT2()).isZero();
        assertThat(noDetails.getT3()).isZero();
        var hidden =
                transactions
                        .execute(
                                admitted,
                                () ->
                                        db.sql(
                                                        "SELECT account_id FROM"
                                                            + " public.gl_native_discover_posting_accounts(:tenant,:book,:posting)")
                                                .bind("tenant", other.fixture().scope().tenant())
                                                .bind("book", other.fixture().scope().book())
                                                .bind("posting", other.effect().posting())
                                                .map((r, m) -> r.get("account_id", UUID.class))
                                                .all()
                                                .collectList())
                        .block();
        assertThat(hidden).isEmpty();
        var ordinary = read(data, List.of(f.debit().toString(), f.credit().toString()), "read");
        assertThat(
                        transactions
                                .execute(
                                        ordinary,
                                        () ->
                                                db.sql(
                                                                "SELECT account_id FROM"
                                                                    + " public.gl_native_discover_posting_accounts(:tenant,:book,:posting)")
                                                        .bind("tenant", f.scope().tenant())
                                                        .bind("book", f.scope().book())
                                                        .bind("posting", data.effect().posting())
                                                        .map(
                                                                (r, m) ->
                                                                        r.get(
                                                                                "account_id",
                                                                                UUID.class))
                                                        .all()
                                                        .collectList())
                                .block())
                .isEmpty();
    }

    @Test
    void retainedReadDoesNotRequireBookReactivationOrAnOpenPeriod() throws Exception {
        var data = data();
        var f = data.fixture();
        try (var c = ownerConnection()) {
            execute(
                    c,
                    "UPDATE public.gl_native_book SET"
                            + " lifecycle='SUSPENDED',control_version=control_version+1 WHERE id='"
                            + f.scope().book()
                            + "';UPDATE public.gl_native_period SET"
                            + " lifecycle='FROZEN',control_version=control_version+1 WHERE id='"
                            + f.period()
                            + "'");
        }
        var admitted = read(data, List.of(f.debit().toString(), f.credit().toString()), "read");
        assertThat(transactions.execute(admitted, () -> rows("gl_native_posting")).block())
                .isEqualTo(1);
    }

    @Test
    void reconcileContextCannotWriteMoneyOrAdministrativeControls() throws Exception {
        var data = data();
        var f = data.fixture();
        var action = UUID.randomUUID().toString();
        var canonical =
                GlPostingRules.canonical(
                        "GL_POSTING_INTENT_V1",
                        List.of(
                                f.scope().tenant().toString(),
                                f.scope().book().toString(),
                                action));
        var intent =
                new AccountingApprovalIntent(
                        f.scope().tenant().toString(),
                        f.scope().book().toString(),
                        "reconcile",
                        GlPostingRules.digest(canonical),
                        canonical,
                        Map.of("actionId", action));
        var admitted =
                authority(f)
                        .authorizeAccounts(
                                intent, List.of(f.debit().toString(), f.credit().toString()), true)
                        .block();
        var writes =
                transactions
                        .execute(
                                admitted,
                                () ->
                                        Flux.concat(
                                                        db.sql(
                                                                        "UPDATE"
                                                                            + " public.gl_native_book_sequence"
                                                                            + " SET last_sequence=last_sequence+1")
                                                                .fetch()
                                                                .rowsUpdated(),
                                                        db.sql(
                                                                        "UPDATE"
                                                                            + " public.gl_native_account"
                                                                            + " SET lifecycle='SUSPENDED',control_version=control_version+1")
                                                                .fetch()
                                                                .rowsUpdated(),
                                                        db.sql(
                                                                        "UPDATE"
                                                                            + " public.gl_native_book"
                                                                            + " SET lifecycle='SUSPENDED',control_version=control_version+1")
                                                                .fetch()
                                                                .rowsUpdated())
                                                .collectList())
                        .block();
        assertThat(writes).containsExactly(0L, 0L, 0L);
        assertThat(transactions.execute(admitted, () -> rows("gl_native_posting")).block())
                .isEqualTo(1);
    }

    @FunctionalInterface
    interface SqlFixture {
        void run(Connection connection) throws Exception;
    }

    static List<String> captured(SqlFixture fixture) throws Exception {
        var commands = new ArrayList<String>();
        var statement =
                (Statement)
                        java.lang.reflect.Proxy.newProxyInstance(
                                Statement.class.getClassLoader(),
                                new Class[] {Statement.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("execute")) {
                                        commands.add((String) args[0]);
                                        return true;
                                    }
                                    if (method.getName().equals("close")) return null;
                                    throw new UnsupportedOperationException(
                                            "Private SQL capture supports execute/close only");
                                });
        var connection =
                (Connection)
                        java.lang.reflect.Proxy.newProxyInstance(
                                Connection.class.getClassLoader(),
                                new Class[] {Connection.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("createStatement"))
                                        return statement;
                                    if (method.getName().equals("close")) return null;
                                    throw new UnsupportedOperationException(
                                            "Private SQL capture supports createStatement/close"
                                                    + " only");
                                });
        fixture.run(connection);
        return commands.stream()
                .map(
                        command ->
                                command.replace(
                                        "'https://synthetic.invalid','private-schema-fixture'",
                                        "'https://synthetic-scoped-gl.invalid','synthetic-scoped-gl-operator'"))
                .toList();
    }

    static Fixture emptyFixture() throws Exception {
        try (var c = ownerConnection()) {
            return fixture(c);
        }
    }

    static Effect scopedEffect(Fixture f, boolean due) throws Exception {
        return scopedEffect(f, due, "12500");
    }

    static Effect scopedEffect(Fixture f, boolean due, String amount) throws Exception {
        try (var c = ownerConnection()) {
            return completeIntentEffect(c, f, due, amount);
        }
    }

    static GlAccountingAuthority.Admission write(Fixture f, Effect effect, String operation) {
        var intent =
                new AccountingApprovalIntent(
                        f.scope().tenant().toString(),
                        f.scope().book().toString(),
                        operation,
                        GlPostingRules.digest(effect.canonical()),
                        effect.canonical(),
                        Map.of("actionId", effect.action().toString()));
        return authority(f)
                .authorizeAccounts(
                        intent, List.of(f.debit().toString(), f.credit().toString()), true)
                .block(Duration.ofSeconds(5));
    }

    static Mono<Long> executeCommands(
            List<String> commands, GlAccountingAuthority.Admission admitted) {
        return Flux.fromIterable(commands)
                .concatMap(
                        command ->
                                db.sql(
                                                command.replace(
                                                        "floor(extract(epoch FROM"
                                                            + " clock_timestamp())*1000)::bigint+60000",
                                                        admitted.validUntilEpochMillis()
                                                                .toString()))
                                        .fetch()
                                        .rowsUpdated())
                .reduce(0L, Long::sum);
    }

    @Test
    void financialWriteRequiresTheExactAuthenticatedActorProvenance() throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, true);
        var admitted = write(f, effect, "reconcile");
        var commands =
                captured(
                        c -> {
                            action(c, f, effect, "ABSENT_CLOSED");
                            event(c, effect, "AUDIT");
                            event(c, effect, "OUTBOX");
                        });
        for (var change :
                List.of(
                        Map.entry("'synthetic-scoped-gl-operator'", "'different-unapproved-actor'"),
                        Map.entry(
                                "'https://synthetic-scoped-gl.invalid'",
                                "'https://different-unapproved-issuer.invalid'"))) {
            var tampered =
                    commands.stream()
                            .map(command -> command.replace(change.getKey(), change.getValue()))
                            .toList();
            assertThatThrownBy(
                            () ->
                                    transactions
                                            .execute(
                                                    admitted,
                                                    () -> executeCommands(tampered, admitted))
                                            .block(Duration.ofSeconds(5)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        try (var c = ownerConnection()) {
            assertThat(count(c, "gl_native_journal_action", f.scope().book())).isZero();
            assertThat(count(c, "gl_native_journal_event", f.scope().book())).isZero();
        }
    }

    @Test
    void currentPostContextCommitsCompleteJournalAndMissingOutboxRollsEverythingBack()
            throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, false, "9007199254740993");
        var admitted = write(f, effect, "post");
        var commands = captured(c -> complete(c, f, effect, 1, null, "9007199254740993"));
        assertThat(
                        transactions
                                .execute(
                                        admitted,
                                        () ->
                                                executeCommands(commands, admitted)
                                                        .then(rows("gl_native_posting")))
                                .block(Duration.ofSeconds(5)))
                .isEqualTo(1);
        try (var c = ownerConnection()) {
            assertThat(count(c, "gl_native_line", f.scope().book())).isEqualTo(2);
            assertThat(count(c, "gl_native_journal_event", f.scope().book())).isEqualTo(2);
        }
        var rollbackFixture = emptyFixture();
        var rollbackEffect = scopedEffect(rollbackFixture, false);
        var rollback = write(rollbackFixture, rollbackEffect, "post");
        var incomplete =
                captured(c -> complete(c, rollbackFixture, rollbackEffect, 1, null, "12500"))
                        .stream()
                        .filter(command -> !command.contains("'OUTBOX'"))
                        .toList();
        assertThatThrownBy(
                        () ->
                                transactions
                                        .execute(
                                                rollback,
                                                () -> executeCommands(incomplete, rollback))
                                        .block(Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);
        try (var c = ownerConnection()) {
            for (var table :
                    List.of(
                            "gl_native_journal_action",
                            "gl_native_posting",
                            "gl_native_entry",
                            "gl_native_line",
                            "gl_native_journal_event"))
                assertThat(count(c, table, rollbackFixture.scope().book())).isZero();
            try (var st = c.createStatement();
                    var rows =
                            st.executeQuery(
                                    "SELECT last_sequence FROM gl_native_book_sequence WHERE"
                                            + " book_id='"
                                            + rollbackFixture.scope().book()
                                            + "'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
        }
    }

    @Test
    void reconcileContextCanCommitOnlyAnAuditedNoEffectClosureAndCannotExtendIt() throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, true);
        var admitted = write(f, effect, "reconcile");
        var closure =
                captured(
                        c -> {
                            action(c, f, effect, "ABSENT_CLOSED");
                            event(c, effect, "AUDIT");
                            event(c, effect, "OUTBOX");
                        });
        assertThat(
                        transactions
                                .execute(
                                        admitted,
                                        () ->
                                                executeCommands(closure, admitted)
                                                        .then(rows("gl_native_journal_action")))
                                .block(Duration.ofSeconds(5)))
                .isEqualTo(1);
        try (var c = ownerConnection()) {
            assertThat(count(c, "gl_native_posting", f.scope().book())).isZero();
            assertThat(count(c, "gl_native_line", f.scope().book())).isZero();
            assertThat(count(c, "gl_native_journal_event", f.scope().book())).isEqualTo(2);
        }
        var money = captured(c -> posting(c, f, effect, 1, null));
        assertThatThrownBy(
                        () ->
                                transactions
                                        .execute(admitted, () -> executeCommands(money, admitted))
                                        .block(Duration.ofSeconds(5)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        try (var c = ownerConnection()) {
            assertThat(count(c, "gl_native_posting", f.scope().book())).isZero();
            assertThat(count(c, "gl_native_journal_event", f.scope().book())).isEqualTo(2);
        }
    }

    @Test
    void balancedEconomicRowsDifferentFromTheApprovedFullIntentCannotCommit() throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, false, "2");
        var admitted = write(f, effect, "post");
        var commands = captured(c -> complete(c, f, effect, 1, null, "1"));
        assertThatThrownBy(
                        () ->
                                transactions
                                        .execute(
                                                admitted, () -> executeCommands(commands, admitted))
                                        .block(Duration.ofSeconds(5)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(
                        error -> {
                            Throwable cause = error;
                            while (cause.getCause() != null) cause = cause.getCause();
                            assertThat(cause).isInstanceOf(io.r2dbc.spi.R2dbcException.class);
                            assertThat(((io.r2dbc.spi.R2dbcException) cause).getSqlState())
                                    .isEqualTo("23514");
                            assertThat(cause.getMessage()).contains("GL_NATIVE_INTENT:");
                        });
        try (var c = ownerConnection()) {
            for (var table :
                    List.of(
                            "gl_native_journal_action",
                            "gl_native_posting",
                            "gl_native_entry",
                            "gl_native_line",
                            "gl_native_journal_event"))
                assertThat(count(c, table, f.scope().book())).isZero();
        }
    }

    static void intentRejected(
            Fixture f, GlAccountingAuthority.Admission admitted, List<String> commands)
            throws Exception {
        intentRejected(f, admitted, commands, "GL_NATIVE_INTENT:");
    }

    static void intentRejected(
            Fixture f, GlAccountingAuthority.Admission admitted, List<String> commands,
            String expectedDiagnostic)
            throws Exception {
        assertThatThrownBy(
                        () ->
                                transactions
                                        .execute(
                                                admitted, () -> executeCommands(commands, admitted))
                                        .block(Duration.ofSeconds(10)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(
                        error -> {
                            Throwable cause = error;
                            while (cause.getCause() != null) cause = cause.getCause();
                            assertThat(cause).isInstanceOf(io.r2dbc.spi.R2dbcException.class);
                            assertThat(((io.r2dbc.spi.R2dbcException) cause).getSqlState())
                                    .isEqualTo("23514");
                            assertThat(cause.getMessage()).contains(expectedDiagnostic);
                        });
        try (var c = ownerConnection()) {
            for (var table :
                    List.of(
                            "gl_native_journal_action",
                            "gl_native_posting",
                            "gl_native_entry",
                            "gl_native_line",
                            "gl_native_journal_event"))
                assertThat(count(c, table, f.scope().book())).isZero();
        }
    }

    static Effect changedOriginal(
            Fixture f,
            Effect effect,
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> change)
            throws Exception {
        var json = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
        var body =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(effect.originalRequest());
        change.accept(body);
        var original = json.writeValueAsString(body);
        var approved =
                GlPostingIntentCodec.decode(
                                f.scope().tenant().toString(),
                                f.scope().book().toString(),
                                original)
                        .validated();
        return new Effect(
                effect.action(),
                effect.posting(),
                effect.receipt(),
                approved.sourceClaimKey(),
                approved.canonicalIntent(),
                effect.deadline(),
                original);
    }

    @Test
    void sameTotalSwappedMembershipAndHeaderOrMemoChangesCannotCommit() throws Exception {
        var mutations =
                List.<java.util.function.UnaryOperator<String>>of(
                        sql -> sql.replace("'Synthetic line'", "'Unapproved different memo'"),
                        sql ->
                                sql.replace(
                                        "'Synthetic complete journal'",
                                        "'Unapproved different entry'"),
                        sql -> sql.replace("'2026-10-21'", "'2026-10-22'"),
                        sql -> sql.replace("'line-1'", "'unapproved-line'"),
                        sql -> sql.replace("'entry-1'", "'unapproved-entry'"),
                        sql ->
                                sql.replace(",'DR','", ",'TEMP','")
                                        .replace(",'CR','", ",'DR','")
                                        .replace(",'TEMP','", ",'CR','"));
        for (var mutation : mutations) {
            var f = emptyFixture();
            var effect = scopedEffect(f, false);
            var admitted = write(f, effect, "post");
            var commands =
                    captured(c -> complete(c, f, effect, 1, null, "12500")).stream()
                            .map(mutation)
                            .toList();
            intentRejected(f, admitted, commands);
        }
    }

    @Test
    void everyFullCurrencyReferenceFieldMustMatchTheAdmittedImmutableRepresentation()
            throws Exception {
        for (var key : List.of("policyId", "currencyId", "isoCode", "version")) {
            var f = emptyFixture();
            var alternate = UUID.randomUUID().toString();
            var effect =
                    changedOriginal(
                            f,
                            scopedEffect(f, false),
                            body -> {
                                for (var line : body.get("entries").get(0).get("lines")) {
                                    var currency =
                                            (com.fasterxml.jackson.databind.node.ObjectNode)
                                                    line.get("currency");
                                    if (key.equals("version")) currency.put(key, 2L);
                                    else
                                        currency.put(
                                                key, key.equals("isoCode") ? "EUR" : alternate);
                                }
                            });
            var selected = effect;
            var admitted = write(f, effect, "post");
            intentRejected(f, admitted, captured(c -> complete(c, f, selected, 1, null, "12500")),
                    "GL_NATIVE_ADMISSION: active exact retained currency required");
        }
    }

    @Test
    void originalBodySourceIdentityAndRawEscapedDuplicateKeysCannotChangeUnderAnApprovedDigest()
            throws Exception {
        for (var kind : List.of("description", "source-key", "duplicate")) {
            var f = emptyFixture();
            var effect = scopedEffect(f, false);
            var admitted = write(f, effect, "post");
            var original = effect.originalRequest();
            String replacement;
            if (kind.equals("description"))
                replacement =
                        original.replace(
                                "Approved exact synthetic journal", "Unapproved replacement");
            else if (kind.equals("duplicate"))
                replacement =
                        original.replace(
                                "\"expectedBookVersion\":1",
                                "\"expectedBookVersion\":1,\""
                                        + (char) 92
                                        + "u0065xpectedBookVersion\":1");
            else replacement = original;
            var commands =
                    captured(c -> complete(c, f, effect, 1, null, "12500")).stream()
                            .map(
                                    sql ->
                                            kind.equals("source-key")
                                                    ? sql.replace(
                                                            "'" + effect.sourceKey() + "'",
                                                            "'" + "f".repeat(64) + "'")
                                                    : sql.replace(
                                                            "'" + original.replace("'", "''") + "'",
                                                            "'"
                                                                    + replacement.replace("'", "''")
                                                                    + "'"))
                            .toList();
            intentRejected(f, admitted, commands);
        }
    }

    @Test
    void physicalInsertionOrderDoesNotChangeTheApprovedLogicalSequenceAndOuterErrorRollsBack()
            throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, false);
        var admitted = write(f, effect, "post");
        var commands = new ArrayList<>(captured(c -> complete(c, f, effect, 1, null, "12500")));
        Collections.swap(commands, 2, 3);
        assertThat(
                        transactions
                                .execute(
                                        admitted,
                                        () ->
                                                executeCommands(commands, admitted)
                                                        .then(rows("gl_native_posting")))
                                .block())
                .isEqualTo(1);
        try (var c = ownerConnection();
                var st = c.createStatement();
                var row =
                        st.executeQuery(
                                "SELECT original_request,canonical_intent,request_shape FROM"
                                        + " gl_native_journal_action WHERE action_id='"
                                        + effect.action()
                                        + "'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo(effect.originalRequest());
            assertThat(row.getString(2)).isEqualTo(effect.canonical());
            assertThat(row.getString(3)).isEqualTo("GL_POSTING_REQUEST_V1");
        }
        var rollbackFixture = emptyFixture();
        var rollbackEffect = scopedEffect(rollbackFixture, false);
        var rollback = write(rollbackFixture, rollbackEffect, "post");
        var rollbackCommands =
                captured(c -> complete(c, rollbackFixture, rollbackEffect, 1, null, "12500"));
        assertThatThrownBy(
                        () ->
                                transactions
                                        .execute(
                                                rollback,
                                                () ->
                                                        executeCommands(rollbackCommands, rollback)
                                                                .then(
                                                                        Mono.error(
                                                                                new IllegalStateException(
                                                                                        "Synthetic"
                                                                                            + " outer"
                                                                                            + " error"
                                                                                            + " before"
                                                                                            + " commit"))))
                                        .block())
                .isInstanceOf(IllegalStateException.class);
        try (var c = ownerConnection()) {
            for (var table :
                    List.of(
                            "gl_native_journal_action",
                            "gl_native_posting",
                            "gl_native_entry",
                            "gl_native_line",
                            "gl_native_journal_event"))
                assertThat(count(c, table, rollbackFixture.scope().book())).isZero();
        }
    }

    @Test
    void committedPostingCannotBeExtendedOrMutatedInALaterAuthorizedTransaction() throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, false);
        var admitted = write(f, effect, "post");
        var commands = captured(c -> complete(c, f, effect, 1, null, "12500"));
        transactions.execute(admitted, () -> executeCommands(commands, admitted)).block();
        var extra = captured(c -> line(c, f, effect, "line-3", f.debit(), "DR", "12500"));
        assertThatThrownBy(
                        () ->
                                transactions
                                        .execute(admitted, () -> executeCommands(extra, admitted))
                                        .block())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var updated =
                transactions
                        .execute(
                                admitted,
                                () ->
                                        db.sql(
                                                        "UPDATE public.gl_native_line SET"
                                                                + " amount_minor='1' WHERE"
                                                                + " action_id=:action")
                                                .bind("action", effect.action())
                                                .fetch()
                                                .rowsUpdated())
                        .block();
        assertThat(updated).isZero();
        try (var c = ownerConnection()) {
            assertThat(count(c, "gl_native_line", f.scope().book())).isEqualTo(2);
            assertThat(count(c, "gl_native_journal_event", f.scope().book())).isEqualTo(2);
        }
    }

    @Test
    void missingWholeEntriesAndExtraBalancedRowsWithIdenticalTotalsCannotCommit() throws Exception {
        var missingFixture = emptyFixture();
        var missing =
                changedOriginal(
                        missingFixture,
                        scopedEffect(missingFixture, false),
                        body -> {
                            var entries =
                                    (com.fasterxml.jackson.databind.node.ArrayNode)
                                            body.get("entries");
                            var second =
                                    (com.fasterxml.jackson.databind.node.ObjectNode)
                                            entries.get(0).deepCopy();
                            second.put("entryKey", "entry-2");
                            entries.add(second);
                        });
        var missingAuthority = write(missingFixture, missing, "post");
        intentRejected(
                missingFixture,
                missingAuthority,
                captured(c -> complete(c, missingFixture, missing, 1, null, "12500")));
        var extraFixture = emptyFixture();
        var extra = scopedEffect(extraFixture, false, "2");
        var extraAuthority = write(extraFixture, extra, "post");
        var extraCommands =
                new ArrayList<>(captured(c -> complete(c, extraFixture, extra, 1, null, "1")));
        extraCommands.addAll(
                captured(
                        c -> {
                            line(c, extraFixture, extra, "line-3", extraFixture.debit(), "DR", "1");
                            line(
                                    c,
                                    extraFixture,
                                    extra,
                                    "line-4",
                                    extraFixture.credit(),
                                    "CR",
                                    "1");
                        }));
        intentRejected(extraFixture, extraAuthority, extraCommands);
    }

    @Test
    void exactSourceClaimCannotBeReusedByAnotherActionEvenWithANewCurrentApproval()
            throws Exception {
        var f = emptyFixture();
        var first = scopedEffect(f, false);
        var firstAuthority = write(f, first, "post");
        transactions
                .execute(
                        firstAuthority,
                        () -> executeCommands(capturedUnchecked(f, first), firstAuthority))
                .block();
        var json = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
        var source = json.readTree(first.originalRequest()).get("source");
        var next = changedOriginal(f, scopedEffect(f, false), body -> body.set("source", source));
        assertThat(next.sourceKey()).isEqualTo(first.sourceKey());
        assertThat(next.canonical()).isNotEqualTo(first.canonical());
        var current = write(f, next, "post");
        var commands = captured(c -> complete(c, f, next, 2, null, "12500"));
        assertThatThrownBy(
                        () ->
                                transactions
                                        .execute(current, () -> executeCommands(commands, current))
                                        .block())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        try (var c = ownerConnection()) {
            assertThat(count(c, "gl_native_journal_action", f.scope().book())).isEqualTo(1);
            assertThat(count(c, "gl_native_posting", f.scope().book())).isEqualTo(1);
            assertThat(count(c, "gl_native_line", f.scope().book())).isEqualTo(2);
            assertThat(count(c, "gl_native_journal_event", f.scope().book())).isEqualTo(2);
        }
    }

    static List<String> capturedUnchecked(Fixture f, Effect effect) {
        try {
            return captured(c -> complete(c, f, effect, 1, null, "12500"));
        } catch (Exception error) {
            throw new IllegalStateException("Synthetic fixture capture failed", error);
        }
    }

    @Test
    void maximumFiveHundredLineIntentCommitsExactlyWithinTheBoundedNativeBudget() throws Exception {
        var f = emptyFixture();
        var effect =
                changedOriginal(
                        f,
                        scopedEffect(f, false),
                        body -> {
                            var lines =
                                    (com.fasterxml.jackson.databind.node.ArrayNode)
                                            body.get("entries").get(0).get("lines");
                            var debit = lines.get(0).deepCopy();
                            var credit = lines.get(1).deepCopy();
                            lines.removeAll();
                            for (int n = 0; n < 500; n++) {
                                var line =
                                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                                (n < 250 ? debit : credit).deepCopy();
                                line.put("lineKey", String.format(Locale.ROOT, "line-%04d", n));
                                line.put("amountMinor", "1");
                                lines.add(line);
                            }
                        });
        var admitted = write(f, effect, "post");
        var commands =
                captured(
                        c -> {
                            action(c, f, effect, "POST");
                            posting(c, f, effect, 1, null);
                            for (int n = 0; n < 500; n++)
                                line(
                                        c,
                                        f,
                                        effect,
                                        String.format(Locale.ROOT, "line-%04d", n),
                                        n < 250 ? f.debit() : f.credit(),
                                        n < 250 ? "DR" : "CR",
                                        "1");
                            event(c, effect, "AUDIT");
                            event(c, effect, "OUTBOX");
                        });
        assertThat(
                        transactions
                                .execute(
                                        admitted,
                                        () ->
                                                executeCommands(commands, admitted)
                                                        .then(rows("gl_native_line")))
                                .block(Duration.ofSeconds(20)))
                .isEqualTo(500);
        try (var c = ownerConnection()) {
            assertThat(count(c, "gl_native_posting", f.scope().book())).isEqualTo(1);
            assertThat(count(c, "gl_native_journal_event", f.scope().book())).isEqualTo(2);
        }
    }

    @Test
    void nativeDateMeaningDoesNotDependOnTheSessionDateDisplayStyle() throws Exception {
        var f = emptyFixture();
        var effect = scopedEffect(f, false);
        var admitted = write(f, effect, "post");
        var commands = new ArrayList<>(captured(c -> complete(c, f, effect, 1, null, "12500")));
        commands.add(0, "SET LOCAL DateStyle='SQL, DMY'");
        assertThat(
                        transactions
                                .execute(
                                        admitted,
                                        () ->
                                                executeCommands(commands, admitted)
                                                        .then(rows("gl_native_posting")))
                                .block())
                .isEqualTo(1);
    }
}
