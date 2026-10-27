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

package io.fireflysoftware.core.accounting.models.repositories.posting.impl;

import io.fireflysoftware.core.accounting.models.entities.posting.*;
import io.fireflysoftware.core.accounting.models.repositories.posting.GlNativePostingRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository("glNativePostingRepository")
@ConditionalOnProperty(name = "firefly.accounting.native-posting.enabled", havingValue = "true")
public final class GlNativePostingRepositoryImpl implements GlNativePostingRepository {
    private final R2dbcEntityTemplate template;

    public GlNativePostingRepositoryImpl(
            @Qualifier("glNativePostingTemplate") R2dbcEntityTemplate template) {
        this.template = template;
    }

    private static Criteria scope(UUID tenant, UUID book) {
        return Criteria.where("tenantId").is(tenant).and("bookId").is(book);
    }

    private <T> Mono<T> one(Class<T> type, Criteria criteria) {
        return template.select(type).matching(Query.query(criteria)).one();
    }

    @Override
    public Mono<GlNativeBook> findBook(UUID tenant, UUID book) {
        return one(GlNativeBook.class, Criteria.where("tenantId").is(tenant).and("id").is(book));
    }

    @Override
    public Mono<GlNativePeriod> findPeriod(UUID tenant, UUID book, UUID period) {
        return one(GlNativePeriod.class, scope(tenant, book).and("id").is(period));
    }

    @Override
    public Mono<GlNativeAccount> findAccount(UUID tenant, UUID book, UUID account) {
        return one(GlNativeAccount.class, scope(tenant, book).and("accountId").is(account));
    }

    @Override
    public Mono<GlNativeCurrencyAdmission> findCurrency(UUID tenant, UUID book, UUID currency) {
        return one(GlNativeCurrencyAdmission.class, scope(tenant, book).and("id").is(currency));
    }

    @Override
    public Mono<GlNativeMapping> findMapping(UUID tenant, UUID book, UUID mapping) {
        return one(GlNativeMapping.class, scope(tenant, book).and("id").is(mapping));
    }

    @Override
    public Mono<GlNativeBookSequence> findSequence(UUID tenant, UUID book) {
        return one(GlNativeBookSequence.class, scope(tenant, book));
    }

    @Override
    public Mono<Long> advanceSequence(UUID tenant, UUID book, long expected) {
        return Mono.defer(
                () -> {
                    if (expected < 0 || expected == Long.MAX_VALUE)
                        return Mono.error(
                                new OptimisticLockingFailureException(
                                        "Native sequence is exhausted"));
                    return template.update(GlNativeBookSequence.class)
                            .matching(
                                    Query.query(
                                            scope(tenant, book).and("lastSequence").is(expected)))
                            .apply(Update.update("lastSequence", expected + 1))
                            .flatMap(
                                    count ->
                                            count == 1
                                                    ? Mono.just(expected + 1)
                                                    : Mono.error(
                                                            count == 0
                                                                    ? new OptimisticLockingFailureException(
                                                                            "Native sequence"
                                                                                + " changed")
                                                                    : new DataIntegrityViolationException(
                                                                            "Native sequence"
                                                                                + " cardinality is"
                                                                                + " invalid")));
                });
    }

    @Override
    public Mono<GlNativeJournalAction> findAction(UUID tenant, UUID book, UUID action) {
        return one(GlNativeJournalAction.class, scope(tenant, book).and("actionId").is(action));
    }

    @Override
    public Mono<GlNativePosting> findPosting(UUID tenant, UUID book, UUID action) {
        return one(GlNativePosting.class, scope(tenant, book).and("actionId").is(action));
    }

    @Override
    public Mono<GlNativeJournalAction> appendAction(GlNativeJournalAction row) {
        return template.insert(row);
    }

    @Override
    public Mono<GlNativePosting> appendPosting(GlNativePosting row) {
        return template.insert(row);
    }

    @Override
    public Mono<GlNativeEntry> appendEntry(GlNativeEntry row) {
        return template.insert(row);
    }

    @Override
    public Mono<GlNativeLine> appendLine(GlNativeLine row) {
        return template.insert(row);
    }

    @Override
    public Mono<GlNativeJournalEvent> appendEvent(GlNativeJournalEvent row) {
        return template.insert(row);
    }
}
