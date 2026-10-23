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

import static org.assertj.core.api.Assertions.*;

import io.fireflysoftware.core.accounting.core.services.posting.v1.*;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.v1.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.util.*;

@Testcontainers
class GlNativeJournalAtomicSchemaIT {
    @Container
    static PostgreSQLContainer database =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("synthetic_gl_atomic")
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

    @BeforeAll
    static void migrate() {
        var configuration =
                Flyway.configure()
                        .dataSource(
                                database.getJdbcUrl(),
                                database.getUsername(),
                                database.getPassword())
                        .locations(
                                System.getProperty(
                                        "gl.migration.location", "classpath:db/migration"));
        if (System.getProperty("gl.migration.target") != null)
            configuration.target(System.getProperty("gl.migration.target"));
        // Retain the pre-V8 foundation protocol and its original incomplete fixture.
        // Current complete-request admission is tested by the scoped RLS suite.
        configuration.target(System.getProperty("gl.migration.target", "7")).load().migrate();
    }

    static Connection connection() throws Exception {
        return DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    record Fixture(Scope scope, UUID debit, UUID credit, UUID period, UUID mapping) {}

    record Effect(
            UUID action,
            UUID posting,
            UUID receipt,
            String sourceKey,
            String canonical,
            long deadline,
            String originalRequest) {
        Effect(
                UUID action,
                UUID posting,
                UUID receipt,
                String sourceKey,
                String canonical,
                long deadline) {
            this(action, posting, receipt, sourceKey, canonical, deadline, null);
        }
    }

    static Fixture fixture(Connection c) throws Exception {
        var scope = book(c);
        var debit = chart(c, scope, "POSTING", null);
        var credit = chart(c, scope, "POSTING", null);
        period(c, scope, "2026-01-01", "2027-01-01");
        UUID period;
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT id FROM gl_native_period WHERE book_id='"
                                        + scope.book()
                                        + "'")) {
            r.next();
            period = r.getObject(1, UUID.class);
        }
        var mapping = UUID.randomUUID();
        execute(
                c,
                "INSERT INTO"
                    + " gl_native_mapping(id,tenant_id,book_id,mapping_reference,revision,definition_digest,canonical_definition,enrollment_action_id)"
                    + " VALUES('"
                        + mapping
                        + "','"
                        + scope.tenant()
                        + "','"
                        + scope.book()
                        + "','synthetic-mapping',1,'"
                        + digest("GL_MAPPING_DEFINITION_V1")
                        + "','GL_MAPPING_DEFINITION_V1','"
                        + UUID.randomUUID()
                        + "'); INSERT INTO gl_native_book_sequence(tenant_id,book_id,last_sequence)"
                        + " VALUES('"
                        + scope.tenant()
                        + "','"
                        + scope.book()
                        + "',0)");
        return new Fixture(scope, debit, credit, period, mapping);
    }

    static Effect effect(Connection c, boolean due) throws Exception {
        var action = UUID.randomUUID();
        long deadline;
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT floor(extract(epoch FROM"
                                        + " clock_timestamp())*1000)::bigint")) {
            r.next();
            deadline = r.getLong(1) + (due ? -1000 : 60000);
        }
        return new Effect(
                action,
                UUID.randomUUID(),
                UUID.randomUUID(),
                digest(UUID.randomUUID().toString()),
                "GL_POSTING_INTENT_V1\n" + action + "\n" + deadline,
                deadline);
    }

    static Effect completeIntentEffect(Connection c, Fixture f, boolean due, String amount)
            throws Exception {
        var base = effect(c, due);
        GlCurrencyReference currency;
        try (var st = c.createStatement();
                var rows =
                        st.executeQuery(
                                "SELECT"
                                    + " policy_id,currency_id,iso_code,policy_version,policy_digest,policy_encoding"
                                    + " FROM public.gl_native_currency_admission WHERE id='"
                                        + f.scope().currency()
                                        + "'")) {
            if (!rows.next())
                throw new IllegalStateException("Exact synthetic fixture currency required");
            currency =
                    new GlCurrencyReference(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getLong(4),
                            rows.getString(5),
                            rows.getString(6));
        }
        var lines =
                List.of(
                        new GlPostingLine(
                                "line-1",
                                f.debit().toString(),
                                1L,
                                f.scope().currency().toString(),
                                1L,
                                currency,
                                2,
                                "DR",
                                amount,
                                "Synthetic line"),
                        new GlPostingLine(
                                "line-2",
                                f.credit().toString(),
                                1L,
                                f.scope().currency().toString(),
                                1L,
                                currency,
                                2,
                                "CR",
                                amount,
                                "Synthetic line"));
        var request =
                new GlPostingRequest(
                        base.action().toString(),
                        f.period().toString(),
                        1L,
                        1L,
                        base.deadline(),
                        "2026-10-21",
                        System.currentTimeMillis(),
                        new GlSourceReference(
                                "MANUAL_ADJUSTMENT",
                                "synthetic-only-object-" + base.action(),
                                "synthetic-only-effect",
                                1L,
                                "a".repeat(64),
                                "PRIVATE_SCHEMA_APPROVAL",
                                0,
                                "synthetic-only-approval",
                                "synthetic-only-full-tuple",
                                System.currentTimeMillis()),
                        f.mapping().toString(),
                        1L,
                        digest("GL_MAPPING_DEFINITION_V1"),
                        "Approved exact synthetic journal",
                        List.of(
                                new GlPostingEntry(
                                        "entry-1", "Synthetic complete journal", lines)));
        var encoded =
                GlPostingIntentCodec.encode(
                        f.scope().tenant().toString(), f.scope().book().toString(), request);
        var approved =
                GlPostingIntentCodec.decode(
                                f.scope().tenant().toString(), f.scope().book().toString(), encoded)
                        .validated();
        return new Effect(
                base.action(),
                base.posting(),
                base.receipt(),
                approved.sourceClaimKey(),
                approved.canonicalIntent(),
                base.deadline(),
                encoded);
    }

    static void action(Connection c, Fixture f, Effect e, String kind) throws Exception {
        var economic =
                e.originalRequest() == null
                        ? "GL_POSTING_ECONOMIC_INTENT_V1\n" + e.sourceKey
                        : GlPostingIntentCodec.decode(
                                        f.scope.tenant().toString(),
                                        f.scope.book().toString(),
                                        e.originalRequest())
                                .validated()
                                .canonicalEconomicIntent();
        execute(
                c,
                "INSERT INTO"
                    + " gl_native_journal_action(action_id,tenant_id,book_id,kind,intent_digest,canonical_intent,economic_digest,canonical_economic_intent,source_claim_key,account_ids,not_after_epoch_millis,receipt_id,actor_issuer,actor_subject,authority_until_epoch_millis,posting_id"
                        + (e.originalRequest() == null ? ")" : ",original_request,request_shape)")
                        + " VALUES('"
                        + e.action
                        + "','"
                        + f.scope.tenant()
                        + "','"
                        + f.scope.book()
                        + "','"
                        + kind
                        + "','"
                        + digest(e.canonical)
                        + "','"
                        + e.canonical
                        + "','"
                        + digest(economic)
                        + "','"
                        + economic
                        + "','"
                        + e.sourceKey
                        + "',ARRAY(SELECT x FROM unnest(ARRAY['"
                        + f.debit
                        + "'::uuid,'"
                        + f.credit
                        + "'::uuid]) x ORDER BY x),"
                        + e.deadline
                        + ",'"
                        + e.receipt
                        + "','https://synthetic.invalid','private-schema-fixture',"
                        + "floor(extract(epoch FROM clock_timestamp())*1000)::bigint+60000,"
                        + (kind.equals("ABSENT_CLOSED") ? "NULL" : "'" + e.posting + "'")
                        + (e.originalRequest() == null
                                ? ""
                                : ",'"
                                        + e.originalRequest().replace("'", "''")
                                        + "','GL_POSTING_REQUEST_V1'")
                        + ")");
    }

    static void posting(Connection c, Fixture f, Effect e, long sequence, UUID original)
            throws Exception {
        execute(
                c,
                "UPDATE gl_native_book_sequence SET last_sequence=last_sequence+1 WHERE book_id='"
                        + f.scope.book()
                        + "'; INSERT INTO"
                        + " gl_native_posting(id,tenant_id,book_id,action_id,kind,journal_sequence,expected_book_version,period_id,expected_period_version,business_date,mapping_id,mapping_version,mapping_digest,original_posting_id)"
                        + " VALUES('"
                        + e.posting
                        + "','"
                        + f.scope.tenant()
                        + "','"
                        + f.scope.book()
                        + "','"
                        + e.action
                        + "','"
                        + (original == null ? "POST" : "REVERSE")
                        + "',"
                        + sequence
                        + ",1,'"
                        + f.period
                        + "',1,'2026-10-21','"
                        + f.mapping
                        + "',1,'"
                        + digest("GL_MAPPING_DEFINITION_V1")
                        + "',"
                        + (original == null ? "NULL" : "'" + original + "'")
                        + ");INSERT INTO"
                        + " gl_native_entry(tenant_id,book_id,action_id,posting_id,entry_key,description)"
                        + " VALUES('"
                        + f.scope.tenant()
                        + "','"
                        + f.scope.book()
                        + "','"
                        + e.action
                        + "','"
                        + e.posting
                        + "','entry-1','Synthetic complete journal')");
    }

    static void line(
            Connection c, Fixture f, Effect e, String key, UUID account, String side, String amount)
            throws Exception {
        execute(
                c,
                "INSERT INTO"
                    + " gl_native_line(tenant_id,book_id,action_id,posting_id,entry_key,line_key,account_id,expected_account_version,currency_admission_id,expected_currency_version,posted_scale,policy_digest,side,amount_minor,memo)"
                    + " SELECT '"
                        + f.scope.tenant()
                        + "','"
                        + f.scope.book()
                        + "','"
                        + e.action
                        + "','"
                        + e.posting
                        + "','entry-1','"
                        + key
                        + "','"
                        + account
                        + "',1,id,1,posted_scale,policy_digest,'"
                        + side
                        + "','"
                        + amount
                        + "','Synthetic line' FROM gl_native_currency_admission WHERE id='"
                        + f.scope.currency()
                        + "'");
    }

    static void event(Connection c, Effect e, String channel) throws Exception {
        execute(
                c,
                "INSERT INTO"
                    + " gl_native_journal_event(tenant_id,book_id,action_id,channel,payload_encoding,payload,payload_digest)"
                    + " SELECT tenant_id,book_id,action_id,'"
                        + channel
                        + "','GL_ACCOUNTING_EVENT_V1',p,encode(sha256(convert_to(p,'UTF8')),'hex')"
                        + " FROM gl_native_journal_action a CROSS JOIN LATERAL (SELECT"
                        + " jsonb_build_object('actionId',a.action_id,'receiptId',a.receipt_id,'intentDigest',a.intent_digest)::text"
                        + " AS p) x WHERE action_id='"
                        + e.action
                        + "'");
    }

    static void complete(
            Connection c, Fixture f, Effect e, long sequence, UUID original, String amount)
            throws Exception {
        action(c, f, e, original == null ? "POST" : "REVERSE");
        posting(c, f, e, sequence, original);
        line(c, f, e, "line-1", f.debit, original == null ? "DR" : "CR", amount);
        line(c, f, e, "line-2", f.credit, original == null ? "CR" : "DR", amount);
        event(c, e, "AUDIT");
        event(c, e, "OUTBOX");
    }

    static long count(Connection c, String table, UUID book) throws Exception {
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT count(*) FROM "
                                        + table
                                        + " WHERE book_id='"
                                        + book
                                        + "'")) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void completeThirtyEightDigitJournalCommitsAndEveryFinancialRowIsImmutable() throws Exception {
        try (var c = connection()) {
            var f = fixture(c);
            var e = effect(c, false);
            c.setAutoCommit(false);
            complete(c, f, e, 1, null, "99999999999999999999999999999999999999");
            c.commit();
            c.setAutoCommit(true);
            assertThat(count(c, "gl_native_posting", f.scope.book())).isEqualTo(1);
            assertThat(count(c, "gl_native_journal_event", f.scope.book())).isEqualTo(2);
            for (var table :
                    List.of(
                            "gl_native_journal_action",
                            "gl_native_posting",
                            "gl_native_entry",
                            "gl_native_line",
                            "gl_native_journal_event")) {
                rejected(c, "DELETE FROM " + table + " WHERE book_id='" + f.scope.book() + "'");
            }
            rejected(
                    c,
                    "UPDATE gl_native_line SET amount_minor='1' WHERE posting_id='"
                            + e.posting
                            + "'");
        }
    }

    @Test
    void incompleteUnbalancedFractionalOrMissingOutboxRollsBackClaimsCounterAndRows()
            throws Exception {
        try (var c = connection()) {
            for (var defect : List.of("MISSING_LINE", "UNBALANCED", "FRACTION", "MISSING_OUTBOX")) {
                var f = fixture(c);
                var e = effect(c, false);
                c.setAutoCommit(false);
                assertThatThrownBy(
                                () -> {
                                    action(c, f, e, "POST");
                                    posting(c, f, e, 1, null);
                                    line(
                                            c,
                                            f,
                                            e,
                                            "line-1",
                                            f.debit,
                                            "DR",
                                            defect.equals("FRACTION") ? "1.5" : "10");
                                    if (!defect.equals("MISSING_LINE"))
                                        line(
                                                c,
                                                f,
                                                e,
                                                "line-2",
                                                f.credit,
                                                "CR",
                                                defect.equals("UNBALANCED") ? "9" : "10");
                                    event(c, e, "AUDIT");
                                    if (!defect.equals("MISSING_OUTBOX")) event(c, e, "OUTBOX");
                                    c.commit();
                                })
                        .isInstanceOf(SQLException.class);
                c.rollback();
                c.setAutoCommit(true);
                for (var table :
                        List.of(
                                "gl_native_journal_action",
                                "gl_native_posting",
                                "gl_native_entry",
                                "gl_native_line",
                                "gl_native_journal_event"))
                    assertThat(count(c, table, f.scope.book())).isZero();
                try (var s = c.createStatement();
                        var r =
                                s.executeQuery(
                                        "SELECT last_sequence FROM gl_native_book_sequence WHERE"
                                                + " book_id='"
                                                + f.scope.book()
                                                + "'")) {
                    r.next();
                    assertThat(r.getLong(1)).isZero();
                }
            }
        }
    }

    @Test
    void closedNoEffectClaimSurvivesSuspensionAndPreventsLaterPostingOrAlternateSourceAction()
            throws Exception {
        try (var c = connection()) {
            var f = fixture(c);
            var e = effect(c, true);
            execute(
                    c,
                    "UPDATE gl_native_book SET lifecycle='SUSPENDED',control_version=2 WHERE id='"
                            + f.scope.book()
                            + "'; UPDATE gl_native_period SET lifecycle='FROZEN',control_version=2"
                            + " WHERE id='"
                            + f.period
                            + "'");
            c.setAutoCommit(false);
            action(c, f, e, "ABSENT_CLOSED");
            event(c, e, "AUDIT");
            event(c, e, "OUTBOX");
            c.commit();
            assertThat(count(c, "gl_native_journal_action", f.scope.book())).isEqualTo(1);
            assertThat(count(c, "gl_native_posting", f.scope.book())).isZero();
            assertThatThrownBy(
                            () -> {
                                posting(c, f, e, 1, null);
                                c.commit();
                            })
                    .isInstanceOf(SQLException.class);
            c.rollback();
            var other = effect(c, false);
            var alternate =
                    new Effect(
                            other.action,
                            other.posting,
                            other.receipt,
                            e.sourceKey,
                            other.canonical,
                            other.deadline);
            assertThatThrownBy(() -> action(c, f, alternate, "POST"))
                    .isInstanceOf(SQLException.class);
            c.rollback();
        }
    }

    @Test
    void reversalRequiresExactOppositeLinesAndRetainsBothImmutableEffects() throws Exception {
        try (var c = connection()) {
            var f = fixture(c);
            var original = effect(c, false);
            c.setAutoCommit(false);
            complete(c, f, original, 1, null, "100");
            c.commit();
            var wrong = effect(c, false);
            assertThatThrownBy(
                            () -> {
                                complete(c, f, wrong, 2, original.posting, "99");
                                c.commit();
                            })
                    .isInstanceOf(SQLException.class);
            c.rollback();
            var reversed = effect(c, false);
            complete(c, f, reversed, 2, original.posting, "100");
            c.commit();
            assertThat(count(c, "gl_native_posting", f.scope.book())).isEqualTo(2);
            assertThat(count(c, "gl_native_journal_event", f.scope.book())).isEqualTo(4);
            var duplicate = effect(c, false);
            assertThatThrownBy(
                            () -> {
                                complete(c, f, duplicate, 3, original.posting, "100");
                                c.commit();
                            })
                    .isInstanceOf(SQLException.class);
            c.rollback();
        }
    }

    @Test
    void literalMalformedAmountsCannotBeRoundedOrNormalizedIntoFinancialValues() throws Exception {
        try (var c = connection()) {
            var f = fixture(c);
            for (var amount : List.of("1.5", "1e2", " 1", "1 ", "+1", "01", "0", "9".repeat(39))) {
                var e = effect(c, false);
                c.setAutoCommit(false);
                assertThatThrownBy(
                                () -> {
                                    action(c, f, e, "POST");
                                    posting(c, f, e, 1, null);
                                    line(c, f, e, "line-1", f.debit, "DR", amount);
                                    c.commit();
                                })
                        .as("literal malformed amount %s", amount)
                        .isInstanceOf(SQLException.class);
                c.rollback();
                c.setAutoCommit(true);
                assertThat(count(c, "gl_native_journal_action", f.scope.book())).isZero();
                assertThat(count(c, "gl_native_line", f.scope.book())).isZero();
            }
        }
    }

    @Test
    void laterTransactionsCannotAppendBalancedLinesOrEventsToCommittedReceipts() throws Exception {
        try (var c = connection()) {
            var f = fixture(c);
            var e = effect(c, false);
            c.setAutoCommit(false);
            complete(c, f, e, 1, null, "10");
            c.commit();
            assertThatThrownBy(
                            () -> {
                                line(c, f, e, "late-1", f.debit, "DR", "1");
                                line(c, f, e, "late-2", f.credit, "CR", "1");
                                c.commit();
                            })
                    .isInstanceOf(SQLException.class);
            c.rollback();
            assertThatThrownBy(() -> action(c, f, e, "POST")).isInstanceOf(SQLException.class);
            c.rollback();
            assertThat(count(c, "gl_native_line", f.scope.book())).isEqualTo(2);
            assertThat(count(c, "gl_native_journal_event", f.scope.book())).isEqualTo(2);
            assertThat(count(c, "gl_native_journal_action", f.scope.book())).isEqualTo(1);
        }
    }
}
