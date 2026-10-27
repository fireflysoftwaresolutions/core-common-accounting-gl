/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

import io.fireflysoftware.core.accounting.core.services.posting.impl.SdkOrganizationBankOwnerObservationAdapter;
import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlBankOwnerSdkAdapterTest {
    final BankOwnerMockResponse fixture = new BankOwnerMockResponse();
    final Clock clock = Clock.fixed(Instant.ofEpochMilli(fixture.now), ZoneOffset.UTC);
    final AtomicLong nanos = new AtomicLong(100);
    final AtomicInteger acquisitions = new AtomicInteger(), requests = new AtomicInteger(), decisions = new AtomicInteger();
    final AtomicReference<SecurityPrincipal> principal = new AtomicReference<>(SecurityPrincipal.builder()
            .issuer("https://gl.example/issuer").subject("actual-staff").tenantId(BankOwnerMockResponse.ACTOR_TENANT)
            .claims(Map.of("exp", clock.instant().plusSeconds(60))).build());
    final AtomicReference<String> mode = new AtomicReference<>("ok");
    final AtomicReference<Jwt> token = new AtomicReference<>();
    final AtomicReference<OAuth2AuthorizeRequest> acquisition = new AtomicReference<>();
    final ClientRegistration registration = ClientRegistration.withRegistrationId("bank-observer")
            .clientId("gl-service").clientSecret("unit-only-not-a-real-secret").authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri("https://org.example/token").build();
    final GlBankOwnerObserverProfile profile = new GlBankOwnerObserverProfile(fixture.expectation().scope(),
            "bank-observer", "00000000-0000-0000-0000-000000000202", URI.create("https://org.example"),
            URI.create("https://org.example/token"), URI.create("https://org.example/jwks"),
            "https://org.example/issuer", "org-audience", "gl-machine", "gl-service",
            "tenant_id", "actor_type", "MACHINE", "client_id", "staff_id");

    GlBankOwnerSdkAdapterTest() throws Exception {
        token.set(jwt(Map.of()));
    }

    Jwt jwt(Map<String, Object> changes) {
        Map<String, Object> claims = new HashMap<>(Map.of("iss", profile.issuer(), "sub", profile.subject(),
                "aud", List.of(profile.audience()), "tenant_id", profile.platformTenantId(), "client_id", profile.clientId(),
                "actor_type", "MACHINE", "exp", clock.instant().plusSeconds(25), "nbf", clock.instant().minusSeconds(1)));
        claims.putAll(changes);
        return new Jwt("controlled-token", clock.instant().minusSeconds(1), (Instant) claims.get("exp"), Map.of("alg", "RS256"), claims);
    }

    SdkOrganizationBankOwnerObservationAdapter adapter() {
        ReactiveOAuth2AuthorizedClientManager manager = request -> {
            acquisitions.incrementAndGet(); acquisition.set(request);
            if (mode.get().equals("acquisition-budget")) nanos.addAndGet(Duration.ofSeconds(11).toNanos());
            return Mono.just(new OAuth2AuthorizedClient(registration, request.getPrincipal().getName(),
                    new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "controlled-token", clock.instant().minusSeconds(1), clock.instant().plusSeconds(20))));
        };
        PolicyDecisionPort policy = (actor, action, resource, context) -> {
            int count = decisions.incrementAndGet();
            if (mode.get().equals("policy-budget")) nanos.addAndGet(Duration.ofSeconds(11).toNanos());
            assertEquals("accounting.gl.book.institution.observe", action);
            assertEquals("accounting/book/" + BankOwnerMockResponse.BOOK, resource);
            assertEquals(fixture.expectation().scope().tenantId(), context.get("resourceTenantId"));
            assertEquals(BankOwnerMockResponse.ACTOR_TENANT, context.get("actorTenantId"));
            assertEquals(fixture.expectation().scope().resource(), context.get("ownerResource"));
            return Mono.just(mode.get().equals("deny") || mode.get().equals("withdraw") && count > 1 ? Decision.deny("unit") : Decision.permit());
        };
        WebClient web = new io.fireflysoftware.core.org.sdk.invoker.ApiClient().getWebClient().mutate().exchangeFunction(request -> {
            requests.incrementAndGet();
            var scope = profile.scope();
            assertEquals("/api/v1/platform/tenants/" + scope.tenantId() + "/banks/" + scope.bankId() + "/enrollments/" + scope.enrollmentId() + "/versions/1/current", request.url().getPath());
            assertEquals(HttpMethod.GET, request.method());
            assertEquals("Bearer controlled-token", request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            if (mode.get().equals("delay")) nanos.addAndGet(Duration.ofSeconds(11).toNanos());
            if (mode.get().equals("actor-change")) principal.set(principal.get().toBuilder().subject("different").build());
            HttpStatus status = mode.get().equals("conflict") ? HttpStatus.CONFLICT : HttpStatus.OK;
            try {
                return Mono.just(ClientResponse.create(status).header("Content-Type", "application/json")
                        .header("Cache-Control", mode.get().equals("cache") ? "public" : "no-store")
                        .body(BankOwnerMockResponse.JSON.writeValueAsString(fixture.response())).build());
            } catch (Exception error) { return Mono.error(error); }
        }).build();
        return new SdkOrganizationBankOwnerObservationAdapter(() -> Mono.just(principal.get()), policy, manager,
                id -> Mono.just(registration), List.of(profile), Map.of("bank-observer", raw -> Mono.just(token.get())), web, clock, nanos::get);
    }

    @Test void genuineSdkUsesExactScopeAndSeparateMachineCredential() {
        var result = adapter().observe(fixture.expectation()).block();
        assertNotNull(result); result.requireCurrent();
        assertEquals(1, acquisitions.get()); assertEquals(1, requests.get()); assertEquals(2, decisions.get());
        assertTrue(acquisition.get().getPrincipal().getName().startsWith("gl-bank-owner-observer/"));
        assertNotEquals(principal.get().subject(), acquisition.get().getPrincipal().getName());
        assertNotEquals(principal.get().tenantId(), result.platformTenantId());
        assertNotEquals(result.snapshot().expectation().scope().tenantId(), result.platformTenantId());
        assertEquals(fixture.now + 5000, result.validUntilEpochMillis());
        assertFalse(result.toString().contains("controlled-token"));
        assertEquals(profile.scope(), result.snapshot().expectation().scope());
    }

    @Test void initialDenialAndNullSharedTenantAcquireNothing() {
        mode.set("deny"); status(403); assertEquals(0, acquisitions.get());
        mode.set("ok"); principal.set(principal.get().toBuilder().tenantId(null).build());
        status(403); assertEquals(0, acquisitions.get()); assertEquals(0, requests.get());
    }

    @Test void currentPolicyWithdrawalAndActorChangeWithholdBody() {
        mode.set("withdraw"); status(403);
        decisions.set(0); mode.set("actor-change"); status(403);
    }

    @Test void outgoingSignedProfileMustMatchPlatformTenantClientKindAndAudience() {
        for (var changed : List.of(Map.<String,Object>of("tenant_id", profile.scope().tenantId()),
                Map.<String,Object>of("client_id", "other"), Map.<String,Object>of("actor_type", "STAFF"),
                Map.<String,Object>of("staff_id", "human"), Map.<String,Object>of("aud", List.of("gl-inbound")),
                Map.<String,Object>of("sub", "other"), Map.<String,Object>of("iss", "https://other.example"))) {
            token.set(jwt(changed)); status(403);
        }
        assertEquals(0, requests.get());
    }

    @Test void scopeMismatchAcquiresNothingAndNoHistoryFallback() {
        var e = fixture.expectation();
        var changed = new GlBankOwnerExpectation(e.actorTenantId(), new GlBankOwnerScope(e.scope().tenantId(),
                UUID.randomUUID().toString(), e.scope().enrollmentId(), 1, e.scope().institutionId(), e.scope().issuerAuthorityId()),
                e.bookId(), e.preparationIntentDigest(), e.approvedRevisionDigest(), e.admissionActionId(), e.ownershipGeneration(),
                e.bankGeneration(), e.sourceGeneration(), e.controlVersion(), e.grantHeadVersion(), e.grantControlVersion(), e.allowedUses(), e.grantDefinitionJson());
        assertThrows(ResponseStatusException.class, () -> adapter().observe(changed).block());
        assertEquals(0, acquisitions.get()); mode.set("conflict"); status(409); assertEquals(1, requests.get());
    }

    @Test void peerNoStoreAndOriginalMonotonicBudgetAreMandatory() {
        mode.set("cache"); status(503); mode.set("delay"); status(403);
    }

    @Test void emittedObservationCannotRenewPastItsOriginalPeerHorizon() {
        var result = adapter().observe(fixture.expectation()).block();
        nanos.addAndGet(Duration.ofSeconds(6).toNanos());
        assertThrows(RuntimeException.class, result::requireCurrent);
    }

    @Test void callerAndSignedCredentialExpiryCapTheReturnedEvidenceWithoutRenewal() {
        token.set(jwt(Map.of("exp", clock.instant().plusSeconds(3))));
        assertEquals(fixture.now + 3000, adapter().observe(fixture.expectation()).block().validUntilEpochMillis());
        principal.set(principal.get().toBuilder().claims(Map.of("exp", clock.instant().plusSeconds(2))).build());
        assertEquals(fixture.now + 2000, adapter().observe(fixture.expectation()).block().validUntilEpochMillis());
    }

    @Test void PolicyAndAcquisitionSpendTheSameOriginalBudgetBeforeOwnerIo() {
        mode.set("policy-budget"); status(403); assertEquals(0, acquisitions.get());
        nanos.set(100); mode.set("acquisition-budget"); status(403); assertEquals(0, requests.get());
    }

    void status(int expected) {
        var error = assertThrows(ResponseStatusException.class, () -> adapter().observe(fixture.expectation()).block());
        assertEquals(expected, error.getStatusCode().value());
    }
}
