/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

import io.fireflysoftware.core.accounting.core.config.posting.GlBankOwnerObservationConfiguration;
import org.fireflyframework.security.api.domain.Decision;
import org.fireflyframework.security.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlBankOwnerConfigurationTest {
    @Test void absentAndExplicitDisabledFeaturesHaveNoBeanOrDependencyRequirement() {
        for (Map<String,Object> values : List.of(Map.<String,Object>of(), Map.<String,Object>of("firefly.accounting.bank-owner-observation.enabled", false))) {
            try (var context = context(values, false)) {
                context.refresh(); assertTrue(context.getBeansOfType(GlBankOwnerObservationPort.class).isEmpty());
            }
        }
    }

    @Test void enabledFeatureWithoutCurrentSecurityOrOAuthFailsStartup() {
        try (var context = context(properties(), false)) { assertThrows(RuntimeException.class, context::refresh); }
    }

    @Test void enabledFeatureRequiresStrictPolicyAndCompleteProfiles() {
        var values = properties(); values.put("firefly.security.resource-server.strict-policy", false);
        try (var context = context(values, true)) { assertThrows(RuntimeException.class, context::refresh); }
        values = properties(); values.remove("firefly.accounting.bank-owner-observation.profiles[0].scope.bank-id");
        try (var context = context(values, true)) { assertThrows(RuntimeException.class, context::refresh); }
    }

    @Test void enabledProductionBeanUsesConfiguredScopeAndNoApprovalFallback() {
        try (var context = context(properties(), true)) {
            context.refresh(); assertEquals(1, context.getBeansOfType(GlBankOwnerObservationPort.class).size());
            assertTrue(context.getBeansOfType(AccountingBookOwnerPort.class).isEmpty());
        }
    }

    @Test void unsafeEndpointsAndAmbiguousClaimMappingsRefuseConstruction() throws Exception {
        var p = new GlBankOwnerSdkAdapterTest().profile;
        assertThrows(IllegalArgumentException.class, () -> new GlBankOwnerObserverProfile(p.scope(), p.registrationId(), p.platformTenantId(),
                java.net.URI.create("http://org.example"), p.tokenUri(), p.jwksUri(), p.issuer(), p.audience(), p.subject(), p.clientId(),
                p.tenantClaim(), p.kindClaim(), p.machineValue(), p.clientClaim(), p.staffClaim()));
        assertThrows(IllegalArgumentException.class, () -> new GlBankOwnerObserverProfile(p.scope(), p.registrationId(), p.platformTenantId(),
                p.baseUri(), p.tokenUri(), p.jwksUri(), p.issuer(), p.audience(), p.subject(), p.clientId(),
                p.tenantClaim(), p.kindClaim(), p.machineValue(), p.tenantClaim(), p.staffClaim()));
    }

    AnnotationConfigApplicationContext context(Map<String,Object> properties, boolean dependencies) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("unit", properties));
        if (dependencies) {
            context.registerBean(SecurityContextPort.class, () -> () -> Mono.empty());
            context.registerBean(PolicyDecisionPort.class, () -> (actor, action, resource, values) -> Mono.just(Decision.deny("unit")));
            context.registerBean(ReactiveOAuth2AuthorizedClientManager.class, () -> mock(ReactiveOAuth2AuthorizedClientManager.class));
            context.registerBean(ReactiveClientRegistrationRepository.class, () -> mock(ReactiveClientRegistrationRepository.class));
        }
        context.register(GlBankOwnerObservationConfiguration.class);
        return context;
    }

    Map<String,Object> properties() {
        Map<String,Object> values = new HashMap<>();
        values.put("firefly.accounting.bank-owner-observation.enabled", true);
        values.put("firefly.security.resource-server.strict-policy", true);
        String prefix = "firefly.accounting.bank-owner-observation.profiles[0].";
        try {
            var p = new GlBankOwnerSdkAdapterTest().profile;
            values.put(prefix + "scope.tenant-id", p.scope().tenantId()); values.put(prefix + "scope.bank-id", p.scope().bankId());
            values.put(prefix + "scope.enrollment-id", p.scope().enrollmentId()); values.put(prefix + "scope.version", 1);
            values.put(prefix + "scope.institution-id", p.scope().institutionId()); values.put(prefix + "scope.issuer-authority-id", p.scope().issuerAuthorityId());
            values.put(prefix + "registration-id", p.registrationId()); values.put(prefix + "platform-tenant-id", p.platformTenantId());
            values.put(prefix + "base-uri", p.baseUri()); values.put(prefix + "token-uri", p.tokenUri()); values.put(prefix + "jwks-uri", p.jwksUri());
            values.put(prefix + "issuer", p.issuer()); values.put(prefix + "audience", p.audience()); values.put(prefix + "subject", p.subject());
            values.put(prefix + "client-id", p.clientId()); values.put(prefix + "tenant-claim", p.tenantClaim()); values.put(prefix + "kind-claim", p.kindClaim());
            values.put(prefix + "machine-value", p.machineValue()); values.put(prefix + "client-claim", p.clientClaim()); values.put(prefix + "staff-claim", p.staffClaim());
        } catch (Exception error) { throw new AssertionError(error); }
        return values;
    }
}
