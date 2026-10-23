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
package io.fireflysoftware.core.accounting.core.services.posting.v1;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Component
public final class GlAccountingDatabaseContext {
    private static final Set<String> READS =
            Set.of("read", "read.resources", "history", "balances");
    private static final Set<String> ACCOUNT_WRITES =
            Set.of("post", "reverse", "reconcile", "account.enroll", "account.control");
    private final DatabaseClient database;

    public GlAccountingDatabaseContext(DatabaseClient database) {
        this.database = database;
    }

    Mono<Void> establish(GlAccountingAuthority.Admission admission) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .onErrorMap(NoTransactionException.class, error -> unavailable())
                .flatMap(
                        manager -> {
                            if (!manager.isActualTransactionActive()
                                    || !manager.hasResource(database.getConnectionFactory()))
                                return Mono.error(unavailable());
                            return Mono.defer(
                                    () -> {
                                        admission.requireCurrent();
                                        var intent = admission.intent();
                                        if (intent == null) throw denied();
                                        GlPostingRules.uuid(intent.tenantId());
                                        GlPostingRules.uuid(intent.bookId());
                                        GlPostingRules.digestField(intent.intentDigest());
                                        if (!GlPostingRules.digest(intent.canonicalIntent())
                                                .equals(intent.intentDigest())) throw denied();
                                        var action =
                                                intent.exactContext().getOrDefault("actionId", "");
                                        var posting =
                                                intent.exactContext().getOrDefault("postingId", "");
                                        if (intent.operation().equals("read.resources")) {
                                            GlPostingRules.uuid(posting);
                                            if (!intent.canonicalIntent()
                                                    .startsWith(
                                                            GlPostingRules.canonical(
                                                                    "GL_READ_CONTEXT_V1",
                                                                    List.of(
                                                                            intent.tenantId(),
                                                                            intent.bookId(),
                                                                            "read.resources",
                                                                            posting))))
                                                throw denied();
                                        }
                                        if (!READS.contains(intent.operation())) {
                                            GlPostingRules.uuid(action);
                                            var prefix =
                                                    GlPostingRules.canonical(
                                                            GlAccountingAuthority.intentEncoding(
                                                                    intent.operation()),
                                                            List.of(
                                                                    intent.tenantId(),
                                                                    intent.bookId(),
                                                                    action));
                                            if (!intent.canonicalIntent().startsWith(prefix)
                                                    || admission.approval() == null
                                                    || !intent.tenantId()
                                                            .equals(admission.approval().tenantId())
                                                    || !intent.bookId()
                                                            .equals(admission.approval().bookId())
                                                    || !intent.operation()
                                                            .equals(
                                                                    admission
                                                                            .approval()
                                                                            .operation())
                                                    || !intent.intentDigest()
                                                            .equals(
                                                                    admission
                                                                            .approval()
                                                                            .intentDigest()))
                                                throw denied();
                                        }
                                        if (admission.accountIds().size() > 500
                                                || (ACCOUNT_WRITES.contains(intent.operation())
                                                        && admission.accountIds().isEmpty()))
                                            throw denied();
                                        for (var id : admission.accountIds())
                                            GlPostingRules.uuid(id);
                                        String accounts =
                                                admission.accountIds().stream()
                                                        .sorted()
                                                        .map(id -> "\"" + id + "\"")
                                                        .collect(Collectors.joining(",", "[", "]"));
                                        return database.sql(
                                                        """
                                                        WITH protected(name) AS (VALUES ('gl_account'),('gl_native_book'),('gl_native_currency_admission'),
                                                          ('gl_native_account'),('gl_native_period'),('gl_native_mapping'),('gl_native_book_sequence'),
                                                          ('gl_native_journal_action'),('gl_native_posting'),('gl_native_entry'),('gl_native_line'),('gl_native_journal_event')),
                                                        relations AS (SELECT c.* FROM protected p JOIN pg_catalog.pg_class c ON c.relname=p.name
                                                          JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'),
                                                        helpers AS (SELECT p.*,o.rolsuper,o.rolbypassrls,o.rolcanlogin,o.rolcreaterole,o.rolcreatedb,o.rolinherit
                                                          FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
                                                          JOIN pg_catalog.pg_roles o ON o.oid=p.proowner WHERE n.nspname='public' AND p.proname IN
                                                          ('gl_native_account_enrollment_guard','gl_native_legacy_account_guard','gl_native_legacy_line_guard',
                                                           'gl_native_period_guard','gl_native_legacy_chart_visible','gl_native_sequence_complete','gl_native_journal_complete','gl_native_action_visible','gl_native_discover_posting_accounts','gl_native_intent_rows_complete_v1'))
                                                        SELECT NOT r.rolsuper AND NOT r.rolbypassrls AND NOT r.rolcreaterole AND NOT r.rolcreatedb
                                                          AND NOT pg_catalog.has_schema_privilege(current_user,'public','CREATE')
                                                          AND NOT pg_catalog.pg_has_role(current_user,'gl_native_guard','MEMBER')
                                                          AND (SELECT count(*)=12 AND bool_and(relrowsecurity AND relforcerowsecurity
                                                            AND NOT pg_catalog.pg_has_role(current_user,relowner,'MEMBER')) FROM relations)
                                                          AND (SELECT count(*)=10 AND bool_and(prosecdef AND NOT rolsuper AND NOT rolbypassrls
                                                            AND NOT rolcanlogin AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolinherit
                                                            AND proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp']) FROM helpers)
                                                          AND :until>floor(extract(epoch FROM clock_timestamp())*1000)::bigint AS allowed
                                                        FROM pg_catalog.pg_roles r WHERE r.rolname=current_user
                                                        """)
                                                .bind("until", admission.validUntilEpochMillis())
                                                .map(
                                                        (row, metadata) ->
                                                                Boolean.TRUE.equals(
                                                                        row.get(
                                                                                "allowed",
                                                                                Boolean.class)))
                                                .one()
                                                .filter(Boolean::booleanValue)
                                                .switchIfEmpty(Mono.error(unavailable()))
                                                .then(
                                                        Mono.defer(
                                                                () -> {
                                                                    admission.requireCurrent();
                                                                    return database.sql(
                                                                                    """
                                                                                    SELECT set_config('firefly.gl.xid',pg_current_xact_id()::text,true),
                                                                                      set_config('firefly.gl.tenant',:tenant,true),set_config('firefly.gl.book',:book,true),
                                                                                      set_config('firefly.gl.operation',:operation,true),set_config('firefly.gl.action',:action,true),
                                                                                      set_config('firefly.gl.posting',:posting,true),
                                                                                      set_config('firefly.gl.intent',:intent,true),set_config('firefly.gl.accounts',:accounts,true),
                                                                                      set_config('firefly.gl.authority_until',:until,true),
                                                                                      set_config('firefly.gl.actor_issuer',:issuer,true),set_config('firefly.gl.actor_subject',:subject,true)
                                                                                    """)
                                                                            .bind(
                                                                                    "issuer",
                                                                                    admission
                                                                                            .actor()
                                                                                            .issuer())
                                                                            .bind(
                                                                                    "subject",
                                                                                    admission
                                                                                            .actor()
                                                                                            .subject())
                                                                            .bind(
                                                                                    "tenant",
                                                                                    intent
                                                                                            .tenantId())
                                                                            .bind(
                                                                                    "book",
                                                                                    intent.bookId())
                                                                            .bind(
                                                                                    "operation",
                                                                                    intent
                                                                                            .operation())
                                                                            .bind("action", action)
                                                                            .bind(
                                                                                    "posting",
                                                                                    posting)
                                                                            .bind(
                                                                                    "intent",
                                                                                    intent
                                                                                            .intentDigest())
                                                                            .bind(
                                                                                    "accounts",
                                                                                    accounts)
                                                                            .bind(
                                                                                    "until",
                                                                                    admission
                                                                                            .validUntilEpochMillis()
                                                                                            .toString())
                                                                            .fetch()
                                                                            .rowsUpdated()
                                                                            .then();
                                                                }));
                                    });
                        });
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Exact current accounting admission required");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Nonprivileged native accounting transaction required");
    }
}
