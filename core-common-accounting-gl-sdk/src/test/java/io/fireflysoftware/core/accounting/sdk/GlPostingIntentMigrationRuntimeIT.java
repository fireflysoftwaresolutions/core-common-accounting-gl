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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.util.*;

@Testcontainers
class GlPostingIntentMigrationRuntimeIT {
    @Container
    static PostgreSQLContainer database =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("synthetic_gl_intent_upgrade")
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

    static Fixture oldFixture;
    static Effect oldEffect;
    static String before;
    static String beforeEvents;

    static Connection owner() throws Exception {
        return DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    static Flyway migration(String target) {
        var config =
                Flyway.configure()
                        .dataSource(
                                database.getJdbcUrl(),
                                database.getUsername(),
                                database.getPassword())
                        .locations(
                                System.getProperty(
                                        "gl.migration.location", "classpath:db/migration"));
        if (target != null) config.target(target);
        return config.load();
    }

    static String one(Connection c, String sql) throws Exception {
        try (var st = c.createStatement();
                var r = st.executeQuery(sql)) {
            assertThat(r.next()).isTrue();
            return r.getString(1);
        }
    }

    @BeforeAll
    static void upgrade() throws Exception {
        migration("7").migrate();
        try (var c = owner()) {
            oldFixture = fixture(c);
            oldEffect = effect(c, false);
            c.setAutoCommit(false);
            complete(c, oldFixture, oldEffect, 1, null, "12500");
            c.commit();
            before =
                    one(
                            c,
                            "SELECT to_jsonb(a)::text FROM gl_native_journal_action a WHERE"
                                + " action_id='"
                                    + oldEffect.action()
                                    + "'");
            beforeEvents =
                    one(
                            c,
                            "SELECT jsonb_agg(to_jsonb(e) ORDER BY channel)::text FROM"
                                + " gl_native_journal_event e WHERE action_id='"
                                    + oldEffect.action()
                                    + "'");
        }
        migration(null).migrate();
    }

    @Test
    void priorFoundationHistoryKeepsExactBytesAndIsNotPromotedToApprovedFinancialProof()
            throws Exception {
        try (var c = owner()) {
            assertThat(
                            one(
                                    c,
                                    "SELECT"
                                        + " (to_jsonb(a)-'request_shape'-'original_request'-'request_document')::text"
                                        + " FROM gl_native_journal_action a WHERE action_id='"
                                            + oldEffect.action()
                                            + "'"))
                    .isEqualTo(before);
            assertThat(
                            one(
                                    c,
                                    "SELECT jsonb_agg(to_jsonb(e) ORDER BY channel)::text FROM"
                                        + " gl_native_journal_event e WHERE action_id='"
                                            + oldEffect.action()
                                            + "'"))
                    .isEqualTo(beforeEvents);
            assertThat(
                            one(
                                    c,
                                    "SELECT request_shape FROM gl_native_journal_action WHERE"
                                        + " action_id='"
                                            + oldEffect.action()
                                            + "'"))
                    .isEqualTo("GL_FOUNDATION_V1");
            assertThat(
                            one(
                                    c,
                                    "SELECT (original_request IS NULL AND request_document IS"
                                        + " NULL)::text FROM gl_native_journal_action WHERE"
                                        + " action_id='"
                                            + oldEffect.action()
                                            + "'"))
                    .isEqualTo("true");
        }
    }

    @Test
    void newActionsCannotOptIntoMissingFoundationBodiesOrAnUnqualifiedReversalShape()
            throws Exception {
        try (var c = owner()) {
            var f = fixture(c);
            for (var kind : List.of("POST", "REVERSE", "ABSENT_CLOSED")) {
                var e = effect(c, kind.equals("ABSENT_CLOSED"));
                assertThatThrownBy(() -> action(c, f, e, kind))
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                error -> {
                                    assertThat(error.getSQLState()).isEqualTo("23514");
                                    assertThat(error.getMessage()).contains("GL_NATIVE_INTENT:");
                                });
            }
            var current = completeIntentEffect(c, f, false, "12500");
            var statements =
                    GlAccountingScopedRlsRuntimeIT.captured(
                            connection -> action(connection, f, current, "POST"));
            for (var statement : statements)
                assertThatThrownBy(
                                () ->
                                        execute(
                                                c,
                                                statement.replace(
                                                        "'GL_POSTING_REQUEST_V1'",
                                                        "'GL_FOUNDATION_V1'")))
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                error -> {
                                    assertThat(error.getSQLState()).isEqualTo("23514");
                                    assertThat(error.getMessage()).contains("GL_NATIVE_INTENT:");
                                });
            var reverse =
                    GlAccountingScopedRlsRuntimeIT.captured(
                            connection -> action(connection, f, current, "REVERSE"));
            for (var statement : reverse)
                assertThatThrownBy(() -> execute(c, statement))
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                error -> {
                                    assertThat(error.getSQLState()).isEqualTo("23514");
                                    assertThat(error.getMessage()).contains("GL_NATIVE_INTENT:");
                                });
        }
    }

    @Test
    void committedFoundationRowsCannotBeCompletedRewrittenOrReclassifiedAfterUpgrade()
            throws Exception {
        try (var c = owner()) {
            c.setAutoCommit(false);
            line(c, oldFixture, oldEffect, "late-line", oldFixture.debit(), "DR", "1");
            assertThatThrownBy(c::commit)
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            error -> assertThat(error.getSQLState()).isEqualTo("23514"));
            c.rollback();
            assertThatThrownBy(
                            () ->
                                    execute(
                                            c,
                                            "UPDATE gl_native_journal_action SET"
                                                + " request_shape='GL_POSTING_REQUEST_V1' WHERE"
                                                + " action_id='"
                                                    + oldEffect.action()
                                                    + "'"))
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            error -> {
                                assertThat(error.getSQLState()).isEqualTo("23514");
                                assertThat(error.getMessage()).contains("GL_NATIVE_IMMUTABLE:");
                            });
            c.rollback();
            assertThat(count(c, "gl_native_line", oldFixture.scope().book())).isEqualTo(2);
            assertThat(
                            one(
                                    c,
                                    "SELECT request_shape FROM gl_native_journal_action WHERE"
                                        + " action_id='"
                                            + oldEffect.action()
                                            + "'"))
                    .isEqualTo("GL_FOUNDATION_V1");
        }
    }
}
