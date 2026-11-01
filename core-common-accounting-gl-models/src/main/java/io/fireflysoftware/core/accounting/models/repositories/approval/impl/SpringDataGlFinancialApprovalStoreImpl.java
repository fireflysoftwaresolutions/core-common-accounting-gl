/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.models.repositories.approval.impl;

import io.fireflysoftware.core.accounting.models.entities.approval.*;
import io.fireflysoftware.core.accounting.models.repositories.approval.*;
import java.util.*;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.core.StatementMapper;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.data.relational.core.query.*;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Mono;

/** Every mutation is mapped and cardinality checked on the exact owner root template. */
public final class SpringDataGlFinancialApprovalStoreImpl implements GlFinancialApprovalStore {
    private final R2dbcEntityTemplate template;
    private final GlFinancialApprovalRepositories.Definitions definitions;
    private final GlFinancialApprovalRepositories.Controls controls;
    private final GlFinancialApprovalRepositories.Transitions transitions;
    private final GlFinancialApprovalRepositories.Integrity integrity;
    private final GlFinancialApprovalRepositories.Mappings mappings;
    public SpringDataGlFinancialApprovalStoreImpl(R2dbcEntityTemplate template) {
        this.template = Objects.requireNonNull(template);
        var factory = new R2dbcRepositoryFactory(template);
        definitions = factory.getRepository(GlFinancialApprovalRepositories.Definitions.class);
        controls = factory.getRepository(GlFinancialApprovalRepositories.Controls.class);
        transitions = factory.getRepository(GlFinancialApprovalRepositories.Transitions.class);
        integrity = factory.getRepository(GlFinancialApprovalRepositories.Integrity.class);
        mappings = factory.getRepository(GlFinancialApprovalRepositories.Mappings.class);
    }
    public void requireTemplate(R2dbcEntityTemplate expected) {
        if (expected != template) throw invalid();
    }
    @Override public Mono<GlFinancialApprovalDefinition> definition(UUID tenant, UUID book, UUID approval) {
        return definitions.findByTenantIdAndBookIdAndId(tenant, book, approval);
    }
    @Override public Mono<GlFinancialApprovalDefinition> definitionForPosting(UUID tenant, UUID book, UUID action, String issuer, String subject) {
        return definitions.findByTenantIdAndBookIdAndPostingActionIdAndMakerIssuerAndMakerSubject(tenant, book, action, issuer, subject);
    }
    @Override public Mono<GlFinancialApprovalControl> control(UUID tenant, UUID book, UUID approval) {
        return controls.findByTenantIdAndBookIdAndApprovalId(tenant, book, approval);
    }
    @Override public Mono<GlFinancialApprovalTransition> transition(UUID tenant, UUID book, UUID command) {
        return transitions.findByTenantIdAndBookIdAndEventId(tenant, book, command);
    }
    @Override public Mono<Void> insertDefinition(GlFinancialApprovalDefinition row) { return insert(row); }
    @Override public Mono<GlFinancialApprovalMapping> mapping(UUID tenant, UUID book, UUID approval) {
        return mappings.findByTenantIdAndBookIdAndApprovalId(tenant,book,approval);
    }
    @Override public Mono<Void> insertMapping(GlFinancialApprovalMapping row) {
        return template.insert(row).filter(row::equals).switchIfEmpty(Mono.error(invalid())).then();
    }
    @Override public Mono<Void> requireCurrentMapping(UUID tenant, UUID book, UUID approval) {
        return integrity.currentMapping(tenant,book,approval).filter(Boolean.TRUE::equals).switchIfEmpty(Mono.error(invalid())).then();
    }
    @Override public Mono<Void> insertControl(GlFinancialApprovalControl row) { return insert(row); }
    @Override public Mono<Void> insertTransition(GlFinancialApprovalTransition row) { return insert(row); }
    @Override public Mono<Void> advance(GlFinancialApprovalControl before, GlFinancialApprovalControl after) {
        if (!before.approvalId().equals(after.approvalId()) || !before.tenantId().equals(after.tenantId())
                || !before.bookId().equals(after.bookId()) || after.revision() != before.revision() + 1) return Mono.error(invalid());
        var where = Criteria.where("approvalId").is(before.approvalId()).and("tenantId").is(before.tenantId())
                .and("bookId").is(before.bookId()).and("state").is(before.state()).and("revision").is(before.revision())
                .and("lastEventId").is(before.lastEventId()).and("lastRecordHash").is(before.lastRecordHash());
        var update = Update.update("state", after.state()).set("revision", after.revision())
                .set("approvedUntilEpochMillis", after.approvedUntilEpochMillis()).set("checkerIssuer", after.checkerIssuer())
                .set("checkerSubject", after.checkerSubject()).set("checkerStaffRealm", after.checkerStaffRealm())
                .set("checkerStaffId", after.checkerStaffId()).set("lastEventId", after.lastEventId())
                .set("lastRecordHash", after.lastRecordHash()).set("transitionDigest", after.transitionDigest());
        return one(template.update(GlFinancialApprovalControl.class).matching(Query.query(where)).apply(update));
    }
    @Override public Mono<Void> requireIntegrity(UUID tenant, UUID book, UUID approval) {
        return integrity.verify(tenant, book, approval).filter(Boolean.TRUE::equals).switchIfEmpty(Mono.error(invalid())).then();
    }
    private Mono<Void> insert(Object row) {
        var strategy = template.getDataAccessStrategy(); var mapper = strategy.getStatementMapper();
        StatementMapper.InsertSpec insert = mapper.createInsert(strategy.getTableName(row.getClass()));
        var outbound = strategy.getOutboundRow(row);
        for (var column : outbound.keySet()) insert = insert.withColumn(column, outbound.get(column));
        return one(template.getDatabaseClient().sql(mapper.getMappedObject(insert)).fetch().rowsUpdated());
    }
    private static Mono<Void> one(Mono<Long> count) { return count.filter(value -> value == 1L).switchIfEmpty(Mono.error(invalid())).then(); }
    private static DataIntegrityViolationException invalid() { return new DataIntegrityViolationException("Exact durable financial approval write required"); }
}
