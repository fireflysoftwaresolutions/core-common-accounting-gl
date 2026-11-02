/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.config.posting;

import io.fireflysoftware.core.accounting.core.services.posting.AccountingPostingSourcePort;
import io.fireflysoftware.core.accounting.core.services.posting.approval.GlFinancialApprovalAuthority;
import io.fireflysoftware.core.accounting.core.services.posting.approval.impl.GlFinancialApprovalLookupImpl;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.*;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.impl.*;
import io.fireflysoftware.core.banking.ledger.sdk.invoker.ApiClient;
import java.time.Clock;
import java.util.HashMap;
import org.fireflyframework.security.spi.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

/** Explicit owner source integration; no fallback client, authority, mapping or generated SDK substitution. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix="firefly.accounting",name={"native-posting.enabled","financial-approval.enabled","ledger-source.enabled"},havingValue="true")
public class GlLedgerPostingSourceConfiguration {
    @Bean
    public GlApprovedLedgerMappingPort approvedLedgerMappings(GlFinancialApprovalLookupImpl lookup,GlFinancialApprovalAuthority authority,
            GlFinancialApprovalConfiguration.TimeSources time) {
        return new CoreApprovedLedgerMappingPortImpl(lookup,authority,time.clock(),time.nanos());
    }
    @Bean
    public AccountingPostingSourcePort ledgerPostingSource(GlApprovedLedgerMappingPort mappings,SecurityContextPort security,PolicyEvaluationPort policies,
            ReactiveOAuth2AuthorizedClientManager clients,ReactiveClientRegistrationRepository registrations,Environment environment,
            GlFinancialApprovalConfiguration.TimeSources time) {
        if(!Boolean.TRUE.equals(environment.getProperty("firefly.security.resource-server.strict-policy",Boolean.class)))
            throw new IllegalStateException("Strict current Ledger source policy required");
        var profiles=Binder.get(environment).bind("firefly.accounting.ledger-source.profiles",Bindable.listOf(GlLedgerObserverProfile.class))
                .orElseThrow(()->new IllegalStateException("Explicit tenant-bound Ledger observer profiles required"));
        var decoders=new HashMap<String,ReactiveJwtDecoder>();
        for(var profile:profiles){
            var decoder=NimbusReactiveJwtDecoder.withJwkSetUri(profile.jwksUri().toString()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
            OAuth2TokenValidator<Jwt> audience=jwt->jwt.getAudience().contains(profile.audience())?OAuth2TokenValidatorResult.success():
                    OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Ledger observer audience required",null));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(profile.issuer()),audience));
            if(decoders.put(profile.registrationId(),decoder)!=null)throw new IllegalArgumentException("Unique Ledger observer registrations required");
        }
        return new SdkLedgerPostingSourcePortImpl(mappings,security,policies,clients,registrations,profiles,decoders,
                new ApiClient().getWebClient(),time.clock(),time.nanos());
    }
}
