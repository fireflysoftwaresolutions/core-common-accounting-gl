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

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
class GlNativeAccountingSchemaIT {
    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("synthetic_gl_native_schema")
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

    static final String HASH = "a".repeat(64);

    static String digest(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @BeforeAll
    static void migrate() throws Exception {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(System.getProperty("gl.migration.location", "classpath:db/migration"));
        if (System.getProperty("gl.migration.target")!=null) configuration.target(System.getProperty("gl.migration.target"));
        configuration.load().migrate();
        try (var c = owner();
                var s = c.createStatement()) {
            s.execute(
                    "CREATE ROLE gl_schema_app LOGIN PASSWORD 'synthetic-only' NOSUPERUSER"
                        + " NOBYPASSRLS;REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON"
                        + " SCHEMA public TO gl_schema_app;GRANT SELECT,INSERT,UPDATE,DELETE ON ALL"
                        + " TABLES IN SCHEMA public TO gl_schema_app");
        }
    }

    static Connection owner() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static void execute(Connection c, String sql) throws Exception {
        try (var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    record Scope(UUID tenant, UUID book, UUID currency) {}

    static Scope book(Connection c) throws Exception {
        var scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        c.setAutoCommit(false);
        execute(
                c,
                "INSERT INTO"
                    + " gl_native_book(id,tenant_id,legal_entity_reference,book_code,definition_digest,canonical_definition,tenant_observation_digest,timezone,calendar_reference,calendar_version,calendar_digest,functional_currency_admission_id,enrollment_action_id)"
                    + " VALUES('"
                        + scope.book
                        + "','"
                        + scope.tenant
                        + "','synthetic-bank','BOOK-"
                        + scope.book
                        + "','"
                        + digest("GL_BOOK_ENROLLMENT_V1")
                        + "','GL_BOOK_ENROLLMENT_V1','"
                        + HASH
                        + "','UTC','calendar',1,'"
                        + HASH
                        + "','"
                        + scope.currency
                        + "','"
                        + UUID.randomUUID()
                        + "')");
        execute(
                c,
                "INSERT INTO"
                    + " gl_native_currency_admission(id,tenant_id,book_id,policy_id,currency_id,iso_code,policy_version,policy_digest,policy_encoding,canonical_policy,posted_scale,definition_digest,canonical_definition,enrollment_action_id)"
                    + " VALUES('"
                        + scope.currency
                        + "','"
                        + scope.tenant
                        + "','"
                        + scope.book
                        + "','"
                        + UUID.randomUUID()
                        + "','"
                        + UUID.randomUUID()
                        + "','USD',1,'"
                        + digest("MASTERS_CURRENCY_POLICY_V1\nretained policy")
                        + "','MASTERS_CURRENCY_POLICY_V1','MASTERS_CURRENCY_POLICY_V1\n"
                        + "retained policy',2,'"
                        + digest("GL_CURRENCY_ADMISSION_V1")
                        + "','GL_CURRENCY_ADMISSION_V1','"
                        + UUID.randomUUID()
                        + "')");
        c.commit();
        c.setAutoCommit(true);
        return scope;
    }

    static UUID sourceChart(Connection c, String kind, UUID parent) throws Exception {
        var id = UUID.randomUUID();
        execute(
                c,
                "INSERT INTO gl_account(id,code,name,type,parent_id,is_leaf,currency) VALUES('"
                        + id
                        + "','NATIVE-"
                        + id
                        + "','Synthetic chart','ASSET',"
                        + (parent == null ? "NULL" : "'" + parent + "'")
                        + ","
                        + kind.equals("POSTING")
                        + ",'USD')");
        return id;
    }

    static UUID chart(Connection c, Scope scope, String kind, UUID parent) throws Exception {
        var id = sourceChart(c, kind, parent);
        enroll(c, scope, id, kind, parent);
        return id;
    }

    static void enroll(Connection c, Scope scope, UUID id, String kind, UUID parent)
            throws Exception {
        execute(
                c,
                "INSERT INTO"
                    + " gl_native_account(account_id,tenant_id,book_id,kind,parent_account_id,currency_admission_id,source_code,source_type,source_currency,definition_digest,canonical_definition,enrollment_action_id)"
                    + " VALUES('"
                        + id
                        + "','"
                        + scope.tenant
                        + "','"
                        + scope.book
                        + "','"
                        + kind
                        + "',"
                        + (parent == null ? "NULL" : "'" + parent + "'")
                        + ","
                        + (kind.equals("POSTING") ? "'" + scope.currency + "'" : "NULL")
                        + ",'NATIVE-"
                        + id
                        + "','ASSET','USD','"
                        + digest("GL_CHART_ACCOUNT_ENROLLMENT_V1")
                        + "','GL_CHART_ACCOUNT_ENROLLMENT_V1','"
                        + UUID.randomUUID()
                        + "')");
    }

    static void rejected(Connection c, String sql) {
        assertThatThrownBy(() -> execute(c, sql)).isInstanceOf(SQLException.class);
    }

    @Test
    void allNewMetadataIsForcedRlsAndInvisibleWithoutValidatedServiceContext() throws Exception {
        try (var c = owner()) {
            book(c);
            for (var table :
                    List.of(
                            "gl_native_book",
                            "gl_native_currency_admission",
                            "gl_native_account",
                            "gl_native_period",
                            "gl_native_mapping")) {
                try (var s = c.createStatement();
                        var r =
                                s.executeQuery(
                                        "SELECT"
                                            + " relrowsecurity,relforcerowsecurity,pg_get_userbyid(relowner)"
                                            + " FROM pg_class WHERE oid='public."
                                                + table
                                                + "'::regclass")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getBoolean(1)).isTrue();
                    assertThat(r.getBoolean(2)).isTrue();
                    assertThat(r.getString(3)).isNotEqualTo("gl_schema_app");
                }
            }
        }
        try (var c =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(), "gl_schema_app", "synthetic-only");
                var s = c.createStatement()) {
            try (var r =
                    s.executeQuery(
                            "SELECT rolsuper,rolbypassrls FROM pg_roles WHERE"
                                    + " rolname=current_user")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBoolean(1)).isFalse();
                assertThat(r.getBoolean(2)).isFalse();
            }
            try (var r = s.executeQuery("SELECT count(*) FROM gl_native_book")) {
                r.next();
                assertThat(r.getInt(1)).isZero();
            }
            assertThat(s.executeUpdate("UPDATE gl_native_book SET lifecycle='SUSPENDED'")).isZero();
        }
    }

    @Test
    void legacyQueriesCannotExposeManagedChartMetadataButKeepUnusedDraftsVisible()
            throws Exception {
        UUID managed, unused;
        try (var c = owner()) {
            var scope = book(c);
            managed = chart(c, scope, "POSTING", null);
            unused = sourceChart(c, "POSTING", null);
        }
        try (var c =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(), "gl_schema_app", "synthetic-only");
                var s = c.createStatement()) {
            try (var r =
                    s.executeQuery("SELECT count(*) FROM gl_account WHERE id='" + managed + "'")) {
                r.next();
                assertThat(r.getInt(1)).isZero();
            }
            try (var r =
                    s.executeQuery("SELECT count(*) FROM gl_account WHERE id='" + unused + "'")) {
                r.next();
                assertThat(r.getInt(1)).isEqualTo(1);
            }
            rejected(c, "SET ROLE gl_native_guard");
            rejected(
                    c,
                    "CREATE OR REPLACE FUNCTION public.gl_native_legacy_line_guard() RETURNS"
                        + " trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'");
            rejected(c, "ALTER TABLE gl_native_book DISABLE ROW LEVEL SECURITY");
        }
    }

    @Test
    void functionalCurrencyRequiresExactSameTenantAndBookAtCommit() throws Exception {
        try (var c = owner()) {
            var a = book(c);
            var b = book(c);
            c.setAutoCommit(false);
            rejected(
                    c,
                    "UPDATE gl_native_book SET functional_currency_admission_id='"
                            + b.currency
                            + "' WHERE id='"
                            + a.book
                            + "'");
            c.rollback();
            assertThatThrownBy(
                            () -> {
                                execute(
                                        c,
                                        "INSERT INTO"
                                            + " gl_native_book(id,tenant_id,legal_entity_reference,book_code,definition_digest,canonical_definition,tenant_observation_digest,timezone,calendar_reference,calendar_version,calendar_digest,functional_currency_admission_id,enrollment_action_id)"
                                            + " SELECT '"
                                                + UUID.randomUUID()
                                                + "',tenant_id,legal_entity_reference,'MISSING',definition_digest,canonical_definition,tenant_observation_digest,timezone,calendar_reference,calendar_version,calendar_digest,'"
                                                + UUID.randomUUID()
                                                + "','"
                                                + UUID.randomUUID()
                                                + "' FROM gl_native_book WHERE id='"
                                                + a.book
                                                + "'");
                                c.commit();
                            })
                    .isInstanceOf(SQLException.class);
            c.rollback();
        }
    }

    @Test
    void definitionsStayImmutableAndOnlyExactControlVersionCanAdvance() throws Exception {
        try (var c = owner()) {
            var scope = book(c);
            rejected(
                    c,
                    "UPDATE gl_native_currency_admission SET posted_scale=3 WHERE id='"
                            + scope.currency
                            + "'");
            rejected(
                    c,
                    "UPDATE gl_native_book SET lifecycle='SUSPENDED' WHERE id='"
                            + scope.book
                            + "'");
            execute(
                    c,
                    "UPDATE gl_native_book SET lifecycle='SUSPENDED',control_version=2 WHERE id='"
                            + scope.book
                            + "' AND control_version=1");
            try (var s = c.createStatement();
                    var r =
                            s.executeQuery(
                                    "SELECT lifecycle,control_version FROM gl_native_book WHERE"
                                            + " id='"
                                            + scope.book
                                            + "'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("SUSPENDED");
                assertThat(r.getLong(2)).isEqualTo(2);
            }
            rejected(
                    c,
                    "UPDATE gl_native_book SET lifecycle='ACTIVE',control_version=4 WHERE id='"
                            + scope.book
                            + "'");
            rejected(
                    c,
                    "DELETE FROM gl_native_currency_admission WHERE id='" + scope.currency + "'");
        }
    }

    @Test
    void sourceHierarchyNeedsExplicitControlParentAndRejectsHistoryOrKindMismatch()
            throws Exception {
        try (var c = owner()) {
            var scope = book(c);
            var root = sourceChart(c, "CONTROL", null);
            var child = sourceChart(c, "CONTROL", root);
            var leaf = sourceChart(c, "POSTING", child);
            enroll(c, scope, root, "CONTROL", null);
            enroll(c, scope, child, "CONTROL", root);
            enroll(c, scope, leaf, "POSTING", child);
            rejected(c, "UPDATE gl_account SET parent_id='" + root + "' WHERE id='" + leaf + "'");
            rejected(c, "UPDATE gl_account SET currency='EUR' WHERE id='" + leaf + "'");
            rejected(c, "UPDATE gl_account SET is_leaf=true WHERE id='" + root + "'");
            rejected(c, "DELETE FROM gl_account WHERE id='" + leaf + "'");
            var wrong = UUID.randomUUID();
            execute(
                    c,
                    "INSERT INTO gl_account(id,code,name,type,is_leaf,currency) VALUES('"
                            + wrong
                            + "','NATIVE-"
                            + wrong
                            + "','Wrong','ASSET',false,'USD')");
            assertThatThrownBy(() -> enroll(c, scope, wrong, "POSTING", null))
                    .isInstanceOf(SQLException.class);
            var other = book(c);
            assertThatThrownBy(() -> chart(c, other, "POSTING", root))
                    .isInstanceOf(SQLException.class);
            var draft = UUID.randomUUID();
            var entry = UUID.randomUUID();
            execute(
                    c,
                    "INSERT INTO journal_batch(id,reference,status,batch_date) VALUES('"
                            + draft
                            + "','native fence','OPEN','2026-01-01'); INSERT INTO"
                            + " journal_entry(id,batch_id,total_amount,entry_date) VALUES('"
                            + entry
                            + "','"
                            + draft
                            + "',1,'2026-01-01')");
            rejected(
                    c,
                    "INSERT INTO journal_line(entry_id,account_id,amount,dr_cr) VALUES('"
                            + entry
                            + "','"
                            + leaf
                            + "',1,'DR')");
            var used = UUID.randomUUID();
            execute(
                    c,
                    "INSERT INTO gl_account(id,code,name,type,is_leaf,currency) VALUES('"
                            + used
                            + "','NATIVE-"
                            + used
                            + "','Used','ASSET',true,'USD'); INSERT INTO"
                            + " journal_line(entry_id,account_id,amount,dr_cr) VALUES('"
                            + entry
                            + "','"
                            + used
                            + "',1,'DR')");
            assertThatThrownBy(() -> enroll(c, scope, used, "POSTING", null))
                    .isInstanceOf(SQLException.class);
        }
    }

    static void period(Connection c, Scope scope, String from, String to) throws Exception {
        execute(
                c,
                "INSERT INTO"
                    + " gl_native_period(id,tenant_id,book_id,from_date,to_date,timezone,calendar_reference,calendar_version,calendar_digest,definition_digest,canonical_definition,enrollment_action_id)"
                    + " VALUES('"
                        + UUID.randomUUID()
                        + "','"
                        + scope.tenant
                        + "','"
                        + scope.book
                        + "','"
                        + from
                        + "','"
                        + to
                        + "','UTC','calendar',1,'"
                        + HASH
                        + "','"
                        + digest("GL_PERIOD_DEFINITION_V1")
                        + "','GL_PERIOD_DEFINITION_V1','"
                        + UUID.randomUUID()
                        + "')");
    }

    @Test
    void overlappingPeriodsSerializeAndOnlyOneCanCommit() throws Exception {
        try (var setup = owner();
                var first = owner();
                var second = owner();
                var executor = Executors.newSingleThreadExecutor()) {
            var scope = book(setup);
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            period(first, scope, "2026-01-01", "2027-01-01");
            var attempt =
                    executor.submit(
                            () -> {
                                try {
                                    period(second, scope, "2026-06-01", "2027-06-01");
                                    second.commit();
                                    return "COMMITTED";
                                } catch (SQLException e) {
                                    second.rollback();
                                    return e.getSQLState();
                                }
                            });
            assertThatThrownBy(() -> attempt.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            first.commit();
            assertThat(attempt.get(5, TimeUnit.SECONDS)).isEqualTo("23514");
            period(setup, scope, "2027-01-01", "2028-01-01");
        }
    }
}
