/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.web.config.posting;

import io.fireflysoftware.core.accounting.core.services.posting.GlBankOwnerObserverProfile;
import org.fireflyframework.security.adapter.opa.OpaPolicyDecisionAdapter;
import org.fireflyframework.security.rs.ResourceServerAutoConfiguration;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Set;

@AutoConfiguration(before = ResourceServerAutoConfiguration.class,
        afterName = "org.springframework.boot.security.oauth2.client.autoconfigure.reactive.ReactiveOAuth2ClientAutoConfiguration")
@ConditionalOnProperty(name = "firefly.accounting.bank-owner-observation.enabled", havingValue = "true", matchIfMissing = false)
@Import(GlBankOwnerPolicyAutoConfiguration.DefaultPolicyBackend.class)
public class GlBankOwnerPolicyAutoConfiguration {
    @Bean
    public InitializingBean glBankOwnerNativeClientConfiguration(ObjectProvider<OAuth2ClientProperties> clients,
            Environment environment) {
        return () -> {
            var configured = clients.getIfAvailable();
            if (configured == null) return;
            var profiles = Binder.get(environment).bind("firefly.accounting.bank-owner-observation.profiles",
                    Bindable.listOf(GlBankOwnerObserverProfile.class)).orElse(List.of());
            for (var profile : profiles) {
                var registration = configured.getRegistration().get(profile.registrationId());
                if (registration == null) continue;
                String method = registration.getClientAuthenticationMethod();
                if (!"client_credentials".equals(registration.getAuthorizationGrantType())
                        || method != null && !Set.of("client_secret_basic", "client_secret_post").contains(method)
                        || !resolved(registration.getClientId(), environment)
                        || !profile.clientId().equals(registration.getClientId())
                        || !resolved(registration.getClientSecret(), environment))
                    throw new IllegalStateException("Explicit matching Bank owner OAuth client credentials required");
            }
        };
    }

    private static boolean resolved(String value, Environment environment) {
        if (value == null || value.isBlank()) return false;
        try { return value.equals(environment.resolveRequiredPlaceholders(value)); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    @ConditionalOnMissingBean(PolicyDecisionPort.class)
    static class DefaultPolicyBackend {
        @Bean
        PolicyDecisionPort glBankOwnerPolicyDecisionPort(Environment environment) {
            var properties = Binder.get(environment).bind("firefly.accounting.bank-owner-observation.policy",
                    Bindable.of(GlBankOwnerPolicyProperties.class)).orElseThrow(() ->
                    new IllegalStateException("Explicit Bank owner policy endpoint and decision path required"));
            return new OpaPolicyDecisionAdapter(WebClient.builder().baseUrl(properties.baseUri().toString()).build(),
                    properties.decisionPath());
        }
    }
}
