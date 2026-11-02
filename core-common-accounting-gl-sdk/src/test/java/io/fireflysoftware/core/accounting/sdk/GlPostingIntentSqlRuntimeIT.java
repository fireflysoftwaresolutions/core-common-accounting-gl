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

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.*;

import io.fireflysoftware.core.accounting.core.services.posting.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

@Testcontainers
class GlPostingIntentSqlRuntimeIT {
    @Container
    static PostgreSQLContainer database =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("synthetic_gl_intent_vectors")
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

    static final JsonMapper JSON = JsonMapper.builder().build();
    static JsonNode vector;
    static String original, tenant, book;

    @BeforeAll
    static void setup() throws Exception {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations(System.getProperty("gl.migration.location", "classpath:db/migration"))
                .load()
                .migrate();
        vector =
                JSON.readTree(
                        Files.readString(
                                Path.of(
                                        System.getProperty(
                                                "gl.intent.vector",
                                                "../core-common-accounting-gl-core/src/test/resources/accounting/posting-intent-v1-golden.json"))));
        original = vector.get("requestJson").textValue();
        tenant = vector.get("tenantId").textValue();
        book = vector.get("bookId").textValue();
        try (var c =
                        DriverManager.getConnection(
                                database.getJdbcUrl(),
                                database.getUsername(),
                                database.getPassword());
                var st = c.createStatement()) {
            st.execute(
                    "CREATE ROLE gl_intent_app LOGIN PASSWORD 'synthetic-only' NOSUPERUSER"
                        + " NOBYPASSRLS NOCREATEROLE NOCREATEDB;REVOKE CREATE ON SCHEMA public FROM"
                        + " PUBLIC;GRANT USAGE ON SCHEMA public TO gl_intent_app");
        }
    }

    static Connection app() throws Exception {
        return DriverManager.getConnection(
                database.getJdbcUrl(), "gl_intent_app", "synthetic-only");
    }

    static void functionsPresent(Connection c) throws Exception {
        try (var st = c.createStatement();
                var rows =
                        st.executeQuery(
                                "SELECT"
                                    + " to_regprocedure('public.gl_native_request_canonical_v1(uuid,uuid,text,boolean)'),to_regprocedure('public.gl_native_request_source_key_v1(uuid,uuid,text)')")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject(1)).as("versioned canonical function required").isNotNull();
            assertThat(rows.getObject(2))
                    .as("versioned source identity function required")
                    .isNotNull();
        }
    }

    static String canonical(Connection c, String body, boolean economic) throws Exception {
        functionsPresent(c);
        try (var st =
                c.prepareStatement(
                        "SELECT public.gl_native_request_canonical_v1(?::uuid,?::uuid,?,?)")) {
            st.setQueryTimeout(5);
            st.setString(1, tenant);
            st.setString(2, book);
            st.setString(3, body);
            st.setBoolean(4, economic);
            try (var rows = st.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    static String sourceKey(Connection c, String body) throws Exception {
        functionsPresent(c);
        try (var st =
                c.prepareStatement(
                        "SELECT public.gl_native_request_source_key_v1(?::uuid,?::uuid,?)")) {
            st.setQueryTimeout(5);
            st.setString(1, tenant);
            st.setString(2, book);
            st.setString(3, body);
            try (var rows = st.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    static String mutate(Consumer<ObjectNode> change) throws Exception {
        var tree = (ObjectNode) JSON.readTree(original);
        change.accept(tree);
        return JSON.writeValueAsString(tree);
    }

    static ObjectNode entry(ObjectNode body) {
        return (ObjectNode) body.get("entries").get(0);
    }

    static ObjectNode line(ObjectNode body) {
        return (ObjectNode) entry(body).get("lines").get(0);
    }

    static void sameAsJava(Connection c, String body) throws Exception {
        var expected = GlPostingIntentCodec.decode(tenant, book, body).validated();
        assertThat(canonical(c, body, false)).isEqualTo(expected.canonicalIntent());
        assertThat(canonical(c, body, true)).isEqualTo(expected.canonicalEconomicIntent());
        assertThat(sourceKey(c, body)).isEqualTo(expected.sourceClaimKey());
    }

    static void rejected(Connection c, String body) throws Exception {
        functionsPresent(c);
        assertThatThrownBy(() -> GlPostingIntentCodec.decode(tenant, book, body))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> canonical(c, body, false))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        error -> {
                            assertThat(error.getSQLState()).isEqualTo("23514");
                            assertThat(error.getMessage()).contains("GL_NATIVE_INTENT:");
                        });
    }

    @Test
    void nativeNonownerMatchesIndependentFullEconomicAndSourceGoldenBytes() throws Exception {
        try (var c = app();
                var st = c.createStatement();
                var role =
                        st.executeQuery(
                                "SELECT rolsuper,rolbypassrls,current_user FROM pg_roles WHERE"
                                    + " rolname=current_user")) {
            assertThat(role.next()).isTrue();
            assertThat(role.getBoolean(1)).isFalse();
            assertThat(role.getBoolean(2)).isFalse();
            assertThat(role.getString(3)).isEqualTo("gl_intent_app");
            assertThat(canonical(c, original, false))
                    .isEqualTo(vector.get("canonicalIntent").textValue());
            assertThat(canonical(c, original, true))
                    .isEqualTo(vector.get("canonicalEconomicIntent").textValue());
            sameAsJava(c, original);
        }
    }

    @Test
    void validJsonFormattingPreservesMeaningWithoutNormalizingArrayOrdering() throws Exception {
        try (var c = app()) {
            sameAsJava(
                    c,
                    JSON.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(JSON.readTree(original)));
            rejected(
                    c,
                    mutate(
                            b -> {
                                var lines = entry(b).withArray("lines");
                                var first = lines.remove(0);
                                lines.add(first);
                            }));
            rejected(
                    c,
                    mutate(
                            b -> {
                                var lines = entry(b).withArray("lines");
                                ((ObjectNode) lines.get(1))
                                        .put("lineKey", lines.get(0).get("lineKey").textValue());
                            }));
        }
    }

    @Test
    void missingNullUnknownAndDuplicateFieldsCannotDisappearThroughJsonb() throws Exception {
        try (var c = app()) {
            var selectors =
                    List.<java.util.function.Function<ObjectNode, ObjectNode>>of(
                            b -> b,
                            b -> (ObjectNode) b.get("source"),
                            GlPostingIntentSqlRuntimeIT::entry,
                            GlPostingIntentSqlRuntimeIT::line,
                            b -> (ObjectNode) line(b).get("currency"));
            for (var selector : selectors) {
                var keys = new ArrayList<String>();
                selector.apply((ObjectNode) JSON.readTree(original))
                        .fieldNames()
                        .forEachRemaining(keys::add);
                for (var key : keys) {
                    rejected(c, mutate(b -> selector.apply(b).remove(key)));
                    rejected(c, mutate(b -> selector.apply(b).putNull(key)));
                }
                rejected(c, mutate(b -> selector.apply(b).put("extra", "unapproved")));
            }
            rejected(
                    c,
                    original.replace(
                            "\"expectedBookVersion\":1",
                            "\"expectedBookVersion\":1,\"expectedBookVersion\":1"));
            rejected(
                    c,
                    original.replace(
                            "\"amountMinor\":\"9007199254740993\"",
                            "\"amountMinor\":\"9007199254740993\",\"amountMinor\":\"9007199254740993\""));
            var escaped = "\\" + "u0061";
            rejected(
                    c,
                    original.replace(
                            "\"actionId\":",
                            "\""
                                    + escaped
                                    + "ctionId\":\"00000000-0000-4000-8000-000000000003\",\"actionId\":"));
        }
    }

    @Test
    void integerLexicalFormsAndUtcBoundsMatchBeforeAnyNumericConversion() throws Exception {
        try (var c = app()) {
            for (var bad :
                    List.of("\"1\"", "1.0", "1e0", "true", "null", "9223372036854775808", "-0"))
                rejected(
                        c,
                        original.replace(
                                "\"expectedBookVersion\":1", "\"expectedBookVersion\":" + bad));
            sameAsJava(
                    c,
                    mutate(
                            b -> {
                                b.put("sourceOccurrenceEpochMillis", GlPostingRules.MIN_TIME);
                                ((ObjectNode) b.get("source"))
                                        .put("observedAtEpochMillis", GlPostingRules.MIN_TIME);
                                b.put("notAfterEpochMillis", 0);
                            }));
            sameAsJava(c, mutate(b -> b.put("notAfterEpochMillis", GlPostingRules.MAX_TIME)));
            rejected(c, mutate(b -> b.put("notAfterEpochMillis", GlPostingRules.MAX_TIME + 1)));
        }
    }

    @Test
    void unicodeScalarsUtf16LimitsAndJavaWhitespaceRulesAreExact() throws Exception {
        try (var c = app()) {
            sameAsJava(c, mutate(b -> b.put("description", "🙂".repeat(250))));
            rejected(c, mutate(b -> b.put("description", "🙂".repeat(251))));
            sameAsJava(
                    c,
                    mutate(
                            b ->
                                    ((ObjectNode) b.get("source"))
                                            .put("owner", String.valueOf((char) 0x00a0))));
            rejected(
                    c,
                    mutate(
                            b ->
                                    ((ObjectNode) b.get("source"))
                                            .put("owner", String.valueOf((char) 0x2003))));
            for (var hex : List.of("D800", "DC00", "0000"))
                rejected(c, original.replace("Golden Ω🙂", "bad-" + "\\" + "u" + hex));
        }
    }

    @Test
    void exactMinorBoundsPerRepresentationBalanceAndReferencesMatch() throws Exception {
        try (var c = app()) {
            for (var bad :
                    List.of(
                            "\"0\"",
                            "\"01\"",
                            "\"+1\"",
                            "\"1.0\"",
                            "\"1e0\"",
                            "1",
                            "1.0",
                            "1e0",
                            "\"" + "9".repeat(39) + "\""))
                rejected(
                        c,
                        original.replace(
                                "\"amountMinor\":\"9007199254740993\"", "\"amountMinor\":" + bad));
            sameAsJava(
                    c,
                    mutate(
                            b -> {
                                for (var value : entry(b).withArray("lines"))
                                    ((ObjectNode) value).put("amountMinor", "9".repeat(38));
                            }));
            rejected(c, mutate(b -> line(b).put("amountMinor", "1")));
            rejected(
                    c,
                    mutate(
                            b ->
                                    ((ObjectNode) line(b).get("currency"))
                                            .put("encodingVersion", "UNSUPPORTED_V2")));
            rejected(c, mutate(b -> ((ObjectNode) line(b).get("currency")).put("isoCode", "EUR")));
        }
    }
}
