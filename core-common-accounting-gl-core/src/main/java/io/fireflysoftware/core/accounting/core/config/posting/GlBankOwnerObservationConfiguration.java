/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.config.posting;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.core.services.posting.impl.SdkOrganizationBankOwnerObservationAdapter;
import io.fireflysoftware.core.org.sdk.invoker.ApiClient;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import java.time.Clock;
import java.util.HashMap;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "firefly.accounting.bank-owner-observation.enabled", havingValue = "true", matchIfMissing = false)
public class GlBankOwnerObservationConfiguration {
    @Bean
    public GlBankOwnerObservationPort glBankOwnerObservationPort(SecurityContextPort contexts, PolicyDecisionPort policies,
            ReactiveOAuth2AuthorizedClientManager clients, ReactiveClientRegistrationRepository registrations, Environment environment) {
        if (!Boolean.TRUE.equals(environment.getProperty("firefly.security.resource-server.strict-policy", Boolean.class)))
            throw new IllegalStateException("Strict current policy required for Bank owner observation");
        var profiles = Binder.get(environment).bind("firefly.accounting.bank-owner-observation.profiles",
                Bindable.listOf(GlBankOwnerObserverProfile.class)).orElseThrow(() -> new IllegalStateException("Bank observer profiles required"));
        var decoders = new HashMap<String, ReactiveJwtDecoder>();
        for (var profile : profiles) {
            var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(profile.jwksUri().toString())
                    .jwsAlgorithm(SignatureAlgorithm.RS256).build();
            OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(profile.audience())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Organization audience required", null));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(profile.issuer()), audience));
            if (decoders.put(profile.registrationId(), decoder) != null)
                throw new IllegalArgumentException("Unique Bank observer registration required");
        }
        return new SdkOrganizationBankOwnerObservationAdapter(contexts, policies, clients, registrations, profiles,
                decoders, new ApiClient().getWebClient(), Clock.systemUTC(), System::nanoTime);
    }
}
