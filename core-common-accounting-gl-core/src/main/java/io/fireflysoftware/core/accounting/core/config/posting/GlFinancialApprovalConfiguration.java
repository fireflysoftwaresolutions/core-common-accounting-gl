/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.config.posting;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.core.services.posting.approval.*;
import io.fireflysoftware.core.accounting.core.services.posting.approval.impl.*;
import io.fireflysoftware.core.accounting.models.repositories.approval.impl.SpringDataGlFinancialApprovalStoreImpl;
import io.fireflysoftware.core.accounting.models.repositories.posting.GlNativePostingResources;
import java.time.*;
import org.fireflyframework.core.transaction.*;
import org.fireflyframework.security.spi.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;

/** Optional owner composition; schema and the restricted audit writer are provisioned separately. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "firefly.accounting", name = {"native-posting.enabled", "financial-approval.enabled"}, havingValue = "true")
public class GlFinancialApprovalConfiguration {
    private final Clock clock = Clock.systemUTC();
    private final java.util.function.LongSupplier nanos = System::nanoTime;

    public record TimeSources(Clock clock,java.util.function.LongSupplier nanos) {}

    @Bean("glFinancialApprovalTimeSources")
    public TimeSources financialApprovalTimeSources() {return new TimeSources(clock,nanos);}

    @Bean
    public CurrentGlFinancialApprovalAuthorityImpl financialApprovalAuthority(
            SecurityContextPort security, PolicyEvaluationPort policies,
            @Value("${firefly.security.resource-server.strict-policy:false}") boolean strict,
            @Value("${firefly.accounting.financial-approval.identity-issuer}") String identityIssuer,
            @Value("${firefly.accounting.financial-approval.approval-issuer}") String approvalIssuer,
            @Value("${firefly.accounting.financial-approval.client-claim}") String clientClaim,
            @Value("${firefly.accounting.financial-approval.canonical-staff-realm}") String staffRealm,
            @Value("${firefly.accounting.financial-approval.maximum-business-lifetime}") Duration maximumLifetime) {
        if (!strict) throw new IllegalStateException("Strict current financial approval policy required");
        return new CurrentGlFinancialApprovalAuthorityImpl(security, policies,
                new CurrentGlFinancialApprovalAuthorityImpl.Profile(identityIssuer, approvalIssuer, clientClaim, staffRealm, maximumLifetime),
                clock, nanos);
    }

    @Bean
    public SpringDataGlFinancialApprovalStoreImpl financialApprovalStore(
            GlNativePostingResources resources) {
        return new SpringDataGlFinancialApprovalStoreImpl(resources.template());
    }

    @Bean
    public GlFinancialApprovalLookupImpl financialApprovalLookup(GlAccountingAuthority authority,
            @Qualifier("glNativePostingTransaction") GlAccountingTransaction transaction,
            SpringDataGlFinancialApprovalStoreImpl store) {
        return new GlFinancialApprovalLookupImpl(authority, transaction, store, clock);
    }

    @Bean
    public GlFinancialApprovalAuditRootImpl financialApprovalAuditRoot(
            GlNativePostingResources ownerResources,
            CurrentGlFinancialApprovalAuthorityImpl authority, SpringDataGlFinancialApprovalStoreImpl store) {
        var template=ownerResources.template();
        var client = template.getDatabaseClient();
        if (!(client.getConnectionFactory() instanceof OwnedLeaseConnectionFactory factory))
            throw new IllegalStateException("Original protected owner root required for financial approval");
        var manager = new R2dbcTransactionManager(factory);
        var resources = BoundedR2dbcResources.verify(factory, client, template, manager);
        store.requireTemplate(template);
        var root = new GlFinancialApprovalAuditRootImpl(resources, client, template, manager,
                new CleanupPolicy(Duration.ofSeconds(2), Duration.ofSeconds(2)), authority, clock, nanos);
        root.requireTemplate(template);
        return root;
    }

    @Bean
    public GlFinancialApprovalService financialApprovalService(CurrentGlFinancialApprovalAuthorityImpl authority,
            GlFinancialApprovalAuditRootImpl root, GlFinancialApprovalLookupImpl lookup,
            SpringDataGlFinancialApprovalStoreImpl store) {
        return new DurableGlFinancialApprovalServiceImpl(authority, root, lookup, store, clock, nanos);
    }

    @Bean
    public AccountingApprovalPort durableAccountingApproval(CurrentGlFinancialApprovalAuthorityImpl authority,
            GlFinancialApprovalLookupImpl lookup) {
        return new DurableAccountingApprovalPortImpl(lookup, authority, authority.approvalIssuer(), clock, nanos);
    }
}
