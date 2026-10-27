/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.impl;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.org.sdk.api.BankEnrollmentOwnerReadsApi;
import io.fireflysoftware.core.org.sdk.invoker.ApiClient;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;

public final class SdkOrganizationBankOwnerObservationAdapter implements GlBankOwnerObservationPort {
    private static final Duration MAXIMUM = Duration.ofSeconds(10);
    private final SecurityContextPort contexts;
    private final PolicyDecisionPort policies;
    private final ReactiveOAuth2AuthorizedClientManager clients;
    private final ReactiveClientRegistrationRepository registrations;
    private final List<GlBankOwnerObserverProfile> profiles;
    private final Map<String, ReactiveJwtDecoder> decoders;
    private final WebClient transport;
    private final Clock clock;
    private final LongSupplier nanos;

    public SdkOrganizationBankOwnerObservationAdapter(SecurityContextPort contexts, PolicyDecisionPort policies,
            ReactiveOAuth2AuthorizedClientManager clients, ReactiveClientRegistrationRepository registrations,
            List<GlBankOwnerObserverProfile> profiles, Map<String, ReactiveJwtDecoder> decoders,
            WebClient transport, Clock clock, LongSupplier nanos) {
        this.contexts = Objects.requireNonNull(contexts);
        this.policies = Objects.requireNonNull(policies);
        this.clients = Objects.requireNonNull(clients);
        this.registrations = Objects.requireNonNull(registrations);
        this.profiles = List.copyOf(profiles);
        this.decoders = Map.copyOf(decoders);
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
        this.nanos = Objects.requireNonNull(nanos);
        if (profiles.isEmpty() || profiles.stream().map(GlBankOwnerObserverProfile::scope).distinct().count() != profiles.size()
                || profiles.stream().map(GlBankOwnerObserverProfile::registrationId).distinct().count() != profiles.size()
                || profiles.stream().anyMatch(profile -> !this.decoders.containsKey(profile.registrationId())))
            throw new IllegalArgumentException("Unique tenant-bound observer registrations and decoders required");
    }

    @Override public Mono<GlBankOwnerObservation> observe(GlBankOwnerExpectation expected) {
        return GlAccountingBoundary.outsideTransaction(() -> Mono.defer(() -> {
            GlBankOwnerObservationRules.requireExpectation(expected);
            long before = nanos.getAsLong();
            Instant started = clock.instant();
            var profile = profiles.stream().filter(value -> value.scope().equals(expected.scope())).findFirst()
                    .orElseThrow(SdkOrganizationBankOwnerObservationAdapter::forbidden);
            var initial = budget(started, started.plus(MAXIMUM), before);
            return contexts.currentPrincipal().switchIfEmpty(Mono.error(forbidden()))
                    .flatMap(actor -> {
                        requireActor(actor, expected);
                        Instant callerUntil = (Instant) actor.claims().get("exp");
                        var callerBudget = budget(started, earliest(initial.until(), callerUntil), before);
                        return authorize(actor, expected, callerBudget)
                                .then(credential(profile, callerBudget))
                                .flatMap(credential -> {
                                    var outgoingBudget = budget(started, earliest(callerBudget.until(), credential.until()), before);
                                    return request(profile, credential.value(), expected, outgoingBudget)
                                            .flatMap(snapshot -> {
                                                var finalBudget = budget(started, earliest(outgoingBudget.until(),
                                                        Instant.ofEpochMilli(snapshot.ownerCurrentUntilEpochMillis())), before);
                                                return contexts.currentPrincipal().filter(actor::equals).switchIfEmpty(Mono.error(forbidden()))
                                                        .flatMap(current -> authorize(current, expected, finalBudget))
                                                        .then(Mono.fromSupplier(() -> {
                                                            finalBudget.remaining();
                                                            return new GlBankOwnerObservation(snapshot, finalBudget.until().toEpochMilli(),
                                                                    profile.issuer(), profile.subject(), profile.platformTenantId(), profile.clientId(), finalBudget.budget());
                                                        }));
                                            });
                                }).timeout(callerBudget.remaining());
                    }).timeout(initial.remaining());
        })).onErrorMap(error -> error instanceof ResponseStatusException ? error : forbidden());
    }

    private Mono<Void> authorize(SecurityPrincipal actor, GlBankOwnerExpectation expected, Window budget) {
        return Mono.defer(() -> {
            requireActor(actor, expected);
            budget.remaining();
            Map<String, Object> context = Map.of("actorTenantId", expected.actorTenantId(),
                    "resourceTenantId", expected.scope().tenantId(), "ownerResource", expected.scope().resource(),
                    "bookId", expected.bookId(), "preparationIntentDigest", expected.preparationIntentDigest(),
                    "approvedRevisionDigest", expected.approvedRevisionDigest(), "institutionId", expected.scope().institutionId(),
                    "issuerAuthorityId", expected.scope().issuerAuthorityId());
            return policies.authorize(actor, "accounting.gl.book.institution.observe", "accounting/book/" + expected.bookId(), context)
                    .filter(decision -> decision.granted() && decision.obligations().isEmpty())
                    .switchIfEmpty(Mono.error(forbidden())).timeout(budget.remaining())
                    .doOnNext(decision -> { requireActor(actor, expected); budget.remaining(); }).then();
        });
    }

    private Mono<Credential> credential(GlBankOwnerObserverProfile profile, Window budget) {
        return registrations.findByRegistrationId(profile.registrationId()).filter(registration ->
                        registration.getAuthorizationGrantType().equals(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        && profile.clientId().equals(registration.getClientId())
                        && profile.tokenUri().toString().equals(registration.getProviderDetails().getTokenUri()))
                .switchIfEmpty(Mono.error(forbidden()))
                // This principal namespaces the standard client cache; signed JWT claims identify the machine.
                .flatMap(registration -> clients.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(profile.registrationId())
                        .principal("gl-bank-owner-observer/" + profile.registrationId()).build())
                        .filter(client -> registration.equals(client.getClientRegistration()))
                        .switchIfEmpty(Mono.error(forbidden())))
                .flatMap(client -> {
                    var token = client.getAccessToken();
                    if (token == null || !OAuth2AccessToken.TokenType.BEARER.equals(token.getTokenType())
                            || token.getExpiresAt() == null || !token.getExpiresAt().isAfter(clock.instant())) return Mono.error(forbidden());
                    return decoders.get(profile.registrationId()).decode(token.getTokenValue()).map(jwt -> {
                        requireMachine(profile, jwt);
                        budget.remaining();
                        return new Credential(token.getTokenValue(), earliest(token.getExpiresAt(), jwt.getExpiresAt()));
                    });
                }).timeout(budget.remaining());
    }

    private Mono<GlBankOwnerObservationRules.Snapshot> request(GlBankOwnerObserverProfile profile, String credential,
            GlBankOwnerExpectation expected, Window budget) {
        return Mono.defer(() -> {
            budget.remaining();
            var client = new ApiClient(transport.mutate().codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + credential).build());
            client.setBasePath(profile.baseUri().toString().replaceAll("/$", ""));
            var scope = expected.scope();
            return new BankEnrollmentOwnerReadsApi(client).getCurrentBankEnrollmentOwnerWithResponseSpec(
                            UUID.fromString(scope.tenantId()), UUID.fromString(scope.bankId()), UUID.fromString(scope.enrollmentId()),
                            Long.toString(scope.version()), null).toEntity(byte[].class)
                    .timeout(budget.remaining())
                    .onErrorMap(WebClientResponseException.class, error -> error.getStatusCode().value() == 409
                            ? new ResponseStatusException(HttpStatus.CONFLICT, "Exact Bank owner is not current") : unavailable())
                    .map(response -> {
                        budget.remaining();
                        var type = response.getHeaders().getContentType();
                        if (response.getStatusCode().value() != 200 || type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)
                                || !Arrays.asList(response.getHeaders().getCacheControl().split(",")).stream()
                                        .map(String::trim).anyMatch("no-store"::equals)) throw unavailable();
                        try { return GlBankOwnerObservationRules.checked(expected, response.getBody(), clock.instant().toEpochMilli()); }
                        catch (RuntimeException error) { throw unavailable(); }
                    });
        });
    }

    private void requireActor(SecurityPrincipal actor, GlBankOwnerExpectation expected) {
        if (actor == null || actor.subject() == null || actor.subject().isBlank() || actor.issuer() == null
                || !expected.actorTenantId().equals(actor.tenantId()) || !(actor.claims().get("exp") instanceof Instant expires)
                || !expires.isAfter(clock.instant())) throw forbidden();
    }

    private void requireMachine(GlBankOwnerObserverProfile profile, Jwt jwt) {
        Instant now = clock.instant();
        if (!profile.issuer().equals(jwt.getClaimAsString("iss")) || !profile.subject().equals(jwt.getSubject())
                || !jwt.getAudience().contains(profile.audience()) || !profile.platformTenantId().equals(jwt.getClaims().get(profile.tenantClaim()))
                || !profile.clientId().equals(jwt.getClaims().get(profile.clientClaim()))
                || !profile.machineValue().equals(jwt.getClaims().get(profile.kindClaim()))
                || jwt.getClaims().containsKey(profile.staffClaim()) || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                || jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now)) throw forbidden();
    }

    private Window budget(Instant started, Instant until, long before) {
        var value = R2dbcOperationBudget.observed(started, until, before, MAXIMUM, clock, nanos);
        value.remaining();
        return new Window(until, value);
    }

    private static Instant earliest(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    private record Window(Instant until, R2dbcOperationBudget budget) {
        Duration remaining() { return budget.remaining(); }
    }
    private record Credential(String value, Instant until) { @Override public String toString() { return "Credential[redacted]"; } }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Current exact Bank observation authority required"); }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Bank owner observation unavailable"); }
}
