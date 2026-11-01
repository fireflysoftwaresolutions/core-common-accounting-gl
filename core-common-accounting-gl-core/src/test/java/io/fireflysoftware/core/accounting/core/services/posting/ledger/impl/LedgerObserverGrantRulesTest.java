/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.ledger.impl;

import static org.assertj.core.api.Assertions.*;

import io.fireflysoftware.core.accounting.core.services.posting.ledger.GlLedgerObserverProfile;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Post-decoder claim rules only; these tests do not claim JWT signature or owner transport acceptance. */
class LedgerObserverGrantRulesTest {
    private static final Instant NOW=Instant.parse("2026-11-01T12:00:00Z");
    private static final String TENANT=UUID.randomUUID().toString();
    private static final UUID SOURCE=UUID.randomUUID(),DESTINATION=UUID.randomUUID();
    private static final Set<UUID> ACCOUNTS=Set.of(SOURCE,DESTINATION);
    private static final GlLedgerObserverProfile PROFILE=new GlLedgerObserverProfile(TENANT,"ledger-observer",
            URI.create("https://synthetic.ledger.invalid"),URI.create("https://synthetic.identity.invalid/token"),URI.create("https://synthetic.identity.invalid/jwks"),
            "https://synthetic.identity.invalid/issuer","ledger","gl-observer","gl-client","tenant","azp","principal_kind","MACHINE","staff_actor_id","scope","funds_accounts",false);
    private static Jwt token(Map<String,Object> replacements) {
        var claims=new HashMap<String,Object>(Map.of("iss",PROFILE.issuer(),"sub",PROFILE.subject(),"aud",List.of(PROFILE.audience()),
                "tenant",TENANT,"azp",PROFILE.clientId(),"principal_kind","MACHINE","scope","funds:read",
                "funds_accounts",List.of(SOURCE.toString(),DESTINATION.toString()),"exp",NOW.plusSeconds(20)));
        claims.putAll(replacements);
        return new Jwt("controlled-post-decoder-value",NOW.minusSeconds(1),(Instant)claims.get("exp"),Map.of("alg","RS256"),claims);
    }
    private static void refused(Map<String,Object> replacements) {
        assertThatThrownBy(()->SdkLedgerPostingSourcePortImpl.requireGrant(PROFILE,token(replacements),ACCOUNTS,NOW))
                .isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(403));
    }
    @Test void bothActuallyGrantedAccountUuidsAndFundsReadScopeAreRequired() {
        assertThatCode(()->SdkLedgerPostingSourcePortImpl.requireGrant(PROFILE,token(Map.of()),ACCOUNTS,NOW)).doesNotThrowAnyException();
        refused(Map.of("funds_accounts",List.of(SOURCE.toString())));
        refused(Map.of("funds_accounts",List.of(DESTINATION.toString())));
        refused(Map.of("scope","funds:confirm"));
        refused(Map.of("scope",List.of("funds:read")));
    }
    @Test void wildcardMalformedOrRequestedResourcesCannotReplaceSignedActualGrants() {
        refused(Map.of("funds_accounts",List.of("*")));
        refused(Map.of("funds_accounts",List.of(SOURCE.toString(),"not-a-uuid")));
        refused(Map.of("funds_accounts",List.of(SOURCE.toString()),"requested_accounts",List.of(SOURCE.toString(),DESTINATION.toString())));
        refused(Map.of("scope","funds:confirm","requested_scope","funds:read"));
    }
    @Test void configuredIssuerSubjectAudienceTenantAndMachineIdentityStayExact() {
        refused(Map.of("iss","https://other.synthetic.invalid"));refused(Map.of("sub","other-machine"));
        refused(Map.of("aud",List.of("other-owner")));refused(Map.of("tenant",UUID.randomUUID().toString()));
        refused(Map.of("azp","other-client"));refused(Map.of("principal_kind","STAFF"));
        refused(Map.of("staff_actor_id",UUID.randomUUID().toString()));
    }
    @Test void expiredOrNotYetValidGrantCannotAuthorizeOwnerRead() {
        refused(Map.of("exp",NOW));refused(Map.of("nbf",NOW.plusSeconds(1)));
    }
}
