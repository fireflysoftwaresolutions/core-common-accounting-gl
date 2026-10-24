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

import static org.assertj.core.api.Assertions.*;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;

import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import reactor.core.publisher.Mono;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
class GlAccountingContextRuntimeIT {
    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("synthetic_gl_context")
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

    static final String TENANT = UUID.randomUUID().toString(),
            BOOK = UUID.randomUUID().toString(),
            ACCOUNT = UUID.randomUUID().toString();
    static final List<String> PROTECTED =
            List.of(
                    "gl_account",
                    "gl_native_book",
                    "gl_native_currency_admission",
                    "gl_native_account",
                    "gl_native_period",
                    "gl_native_mapping",
                    "gl_native_book_sequence",
                    "gl_native_journal_action",
                    "gl_native_posting",
                    "gl_native_entry",
                    "gl_native_line",
                    "gl_native_journal_event");
    static final List<String> CONTEXT =
            List.of(
                    "tenant",
                    "book",
                    "operation",
                    "action",
                    "posting",
                    "intent",
                    "accounts",
                    "authority_until",
                    "xid",
                    "actor_issuer",
                    "actor_subject");
    static ConnectionPool pool;
    static DatabaseClient db;
    static GlAccountingTransaction transactions;
    static GlAccountingAuthority authority;

    @BeforeAll
    static void setup() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(System.getProperty("gl.migration.location", "classpath:db/migration"))
                .load()
                .migrate();
        sql(
                "CREATE ROLE gl_context_app LOGIN PASSWORD 'synthetic-only' NOSUPERUSER NOBYPASSRLS"
                    + " NOCREATEROLE NOCREATEDB;REVOKE CREATE ON SCHEMA public FROM PUBLIC;GRANT"
                    + " USAGE ON SCHEMA public TO gl_context_app;GRANT SELECT,INSERT,UPDATE,DELETE"
                    + " ON ALL TABLES IN SCHEMA public TO gl_context_app");
        pool =
                new ConnectionPool(
                        ConnectionPoolConfiguration.builder(
                                        new PostgresqlConnectionFactory(
                                                PostgresqlConnectionConfiguration.builder()
                                                        .host(postgres.getHost())
                                                        .port(postgres.getMappedPort(5432))
                                                        .database(postgres.getDatabaseName())
                                                        .username("gl_context_app")
                                                        .password("synthetic-only")
                                                        .build()))
                                .initialSize(0)
                                .maxSize(1)
                                .maxAcquireTime(Duration.ofSeconds(5))
                                .build());
        db = DatabaseClient.create(pool);
        transactions =
                new GlAccountingTransaction(
                        new R2dbcTransactionManager(pool), new GlAccountingDatabaseContext(db));
        var beans = new StaticListableBeanFactory();
        beans.addBean(
                "actor",
                (SecurityContextPort)
                        () ->
                                Mono.just(
                                        SecurityPrincipal.builder()
                                                .subject("synthetic-gl-context-operator")
                                                .issuer("https://synthetic-gl-context.invalid")
                                                .claims(
                                                        Map.of(
                                                                "exp",
                                                                Instant.now().plusSeconds(60)))
                                                .build()));
        beans.addBean(
                "policy",
                (PolicyDecisionPort)
                        (actor, action, resource, context) ->
                                Mono.just(
                                        TENANT.equals(context.get("tenantId"))
                                                        && BOOK.equals(context.get("bookId"))
                                                        && Set.of(
                                                                        "accounting/book/" + BOOK,
                                                                        "accounting/account/"
                                                                                + ACCOUNT)
                                                                .contains(resource)
                                                ? Decision.permit()
                                                : Decision.deny(
                                                        "Synthetic exact context fixture denied")));
        beans.addBean(
                "approval",
                (AccountingApprovalPort)
                        (actor, intent) ->
                                Mono.just(
                                        new AccountingApproval(
                                                intent.tenantId(),
                                                intent.bookId(),
                                                intent.operation(),
                                                intent.intentDigest(),
                                                actor.issuer(),
                                                actor.subject(),
                                                "https://synthetic-gl-approval.invalid",
                                                "synthetic-exact-context-only",
                                                "b".repeat(64),
                                                Instant.now().plusSeconds(45).toEpochMilli())));
        authority =
                new GlAccountingAuthority(
                        beans.getBeanProvider(SecurityContextPort.class),
                        beans.getBeanProvider(PolicyDecisionPort.class),
                        beans.getBeanProvider(AccountingApprovalPort.class),
                        beans.getBeanProvider(AccountingBookOwnerPort.class),
                        new MockEnvironment()
                                .withProperty(
                                        "firefly.security.resource-server.strict-policy", "true")
                                .withProperty(
                                        "firefly.accounting.approval.trusted-issuers",
                                        "https://synthetic-gl-approval.invalid"));
    }

    @AfterAll
    static void teardown() {
        if (pool != null) pool.dispose();
    }

    static void sql(String command) throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var s = c.createStatement()) {
            s.execute(command);
        }
    }

    static GlAccountingAuthority.Admission admission() {
        var action = UUID.randomUUID().toString();
        var canonical =
                GlPostingRules.canonical("GL_POSTING_INTENT_V1", List.of(TENANT, BOOK, action));
        var intent =
                new AccountingApprovalIntent(
                        TENANT,
                        BOOK,
                        "post",
                        GlPostingRules.digest(canonical),
                        canonical,
                        Map.of("actionId", action));
        return authority
                .authorizeAccounts(intent, List.of(ACCOUNT), true)
                .block(Duration.ofSeconds(5));
    }

    static Mono<Map<String, String>> values() {
        var columns =
                CONTEXT.stream()
                        .map(
                                k ->
                                        "coalesce(current_setting('firefly.gl."
                                                + k
                                                + "',true),'') AS "
                                                + k)
                        .collect(java.util.stream.Collectors.joining(","));
        return db.sql("SELECT " + columns)
                .map(
                        (r, m) -> {
                            Map<String, String> result = new LinkedHashMap<>();
                            for (var key : CONTEXT) result.put(key, r.get(key, String.class));
                            return result;
                        })
                .one();
    }

    static void cleared() {
        assertThat(values().block(Duration.ofSeconds(5)).values()).allMatch(String::isEmpty);
    }

    static void denied(Mono<?> result, int status) {
        assertThatThrownBy(() -> result.block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void currentNativeConnectionUsesAnExplicitNonownerRoleAndTransactionLocalContext() {
        var role =
                db.sql(
                                "SELECT"
                                    + " rolsuper,rolbypassrls,rolcreaterole,rolcreatedb,current_user"
                                    + " AS role FROM pg_roles WHERE rolname=current_user")
                        .map(
                                (r, m) ->
                                        Map.of(
                                                "role",
                                                r.get("role", String.class),
                                                "super",
                                                r.get("rolsuper", Boolean.class),
                                                "bypass",
                                                r.get("rolbypassrls", Boolean.class),
                                                "createRole",
                                                r.get("rolcreaterole", Boolean.class),
                                                "createDb",
                                                r.get("rolcreatedb", Boolean.class)))
                        .one()
                        .block(Duration.ofSeconds(5));
        assertThat(role)
                .containsEntry("role", "gl_context_app")
                .containsEntry("super", false)
                .containsEntry("bypass", false)
                .containsEntry("createRole", false)
                .containsEntry("createDb", false);
        var owners =
                db.sql(
                                "SELECT count(*) AS n FROM pg_class c JOIN pg_namespace n ON"
                                    + " n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname"
                                    + " IN ('"
                                        + String.join("','", PROTECTED)
                                        + "') AND c.relrowsecurity AND c.relforcerowsecurity AND"
                                        + " NOT pg_has_role(current_user,c.relowner,'MEMBER')")
                        .map((r, m) -> r.get("n", Long.class))
                        .one()
                        .block(Duration.ofSeconds(5));
        assertThat(owners).isEqualTo(12);
        var admitted = admission();
        var retained =
                transactions
                        .execute(admitted, GlAccountingContextRuntimeIT::values)
                        .block(Duration.ofSeconds(5));
        assertThat(retained)
                .containsEntry("tenant", TENANT)
                .containsEntry("book", BOOK)
                .containsEntry("operation", "post")
                .containsEntry("action", admitted.intent().exactContext().get("actionId"))
                .containsEntry("intent", admitted.intent().intentDigest())
                .containsEntry("accounts", "[\"" + ACCOUNT + "\"]")
                .containsEntry("posting", "");
        cleared();
    }

    @Test
    void rollbackAndEmptyBusinessResultLeaveNoContextOnSingleConnectionPoolReuse() {
        var original = new IllegalStateException("synthetic-rollback-only");
        assertThatThrownBy(
                        () ->
                                transactions
                                        .execute(
                                                admission(),
                                                () -> values().then(Mono.error(original)))
                                        .block(Duration.ofSeconds(5)))
                .isSameAs(original);
        cleared();
        denied(transactions.execute(admission(), Mono::empty), 503);
        cleared();
    }

    @Test
    void cancellationAfterContextEstablishmentReleasesAndClearsTheSamePooledConnection()
            throws Exception {
        var started = new CountDownLatch(1);
        var running =
                transactions
                        .execute(
                                admission(),
                                () ->
                                        values().flatMap(
                                                        v -> {
                                                            assertThat(v.get("tenant"))
                                                                    .isEqualTo(TENANT);
                                                            started.countDown();
                                                            return Mono.never();
                                                        }))
                        .subscribe();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        running.dispose();
        cleared();
    }

    @Test
    void partialOwnershipOfEveryProtectedRelationFailsClosedBeforeBusinessWork() throws Exception {
        for (var table : PROTECTED) {
            try {
                sql(
                        "GRANT CREATE ON SCHEMA public TO gl_context_app;ALTER TABLE public."
                                + table
                                + " OWNER TO gl_context_app;REVOKE CREATE ON SCHEMA public FROM"
                                + " gl_context_app");
                denied(transactions.execute(admission(), () -> Mono.just("must-not-run")), 503);
                cleared();
            } finally {
                sql(
                        "ALTER TABLE public."
                                + table
                                + " OWNER TO gl_migration;REVOKE CREATE ON SCHEMA public FROM"
                                + " gl_context_app");
            }
        }
    }

    @Test
    void schemaCreationPrivilegeAndLoginHelperOwnerAreBothRejected() throws Exception {
        try {
            sql("GRANT CREATE ON SCHEMA public TO gl_context_app");
            denied(transactions.execute(admission(), () -> Mono.just("must-not-run")), 503);
        } finally {
            sql("REVOKE CREATE ON SCHEMA public FROM gl_context_app");
        }
        cleared();
        try {
            sql(
                    "GRANT CREATE ON SCHEMA public TO gl_context_app;ALTER FUNCTION"
                            + " public.gl_native_legacy_chart_visible(uuid) OWNER TO"
                            + " gl_context_app;REVOKE CREATE ON SCHEMA public FROM gl_context_app");
            denied(transactions.execute(admission(), () -> Mono.just("must-not-run")), 503);
        } finally {
            sql(
                    "GRANT CREATE ON SCHEMA public TO gl_native_guard;ALTER FUNCTION"
                        + " public.gl_native_legacy_chart_visible(uuid) OWNER TO"
                        + " gl_native_guard;REVOKE CREATE ON SCHEMA public FROM gl_native_guard");
        }
        cleared();
    }

    @Test
    void expiredAdmissionCannotLeaveStateOrEnterBusinessWork() {
        var current = admission();
        var expired =
                new GlAccountingAuthority.Admission(
                        current.actor(),
                        current.approval(),
                        System.currentTimeMillis() - 1,
                        current.intent(),
                        current.accountIds());
        denied(transactions.execute(expired, () -> Mono.just("must-not-run")), 403);
        cleared();
    }

    @Test
    void helperDatabaseCreationPrivilegeIsRejectedBeforeCurrentContextAdmission() throws Exception {
        try {
            sql("ALTER ROLE gl_native_guard CREATEDB");
            denied(transactions.execute(admission(), () -> Mono.just("must-not-run")), 503);
        } finally {
            sql("ALTER ROLE gl_native_guard NOCREATEDB");
        }
        cleared();
    }
}
