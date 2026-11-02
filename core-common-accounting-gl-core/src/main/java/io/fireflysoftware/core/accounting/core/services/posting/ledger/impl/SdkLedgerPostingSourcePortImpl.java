/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.ledger.impl;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.*;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.*;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.*;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsContracts;
import io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec;
import io.fireflysoftware.core.banking.ledger.sdk.api.LedgerFundsApi;
import io.fireflysoftware.core.banking.ledger.sdk.invoker.ApiClient;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Observes the genuine typed owner receipt using issuer-granted resources, after Core four-eyes approval. */
public final class SdkLedgerPostingSourcePortImpl implements AccountingPostingSourcePort {
    private static final Logger LOG=LoggerFactory.getLogger(SdkLedgerPostingSourcePortImpl.class);
    private final GlApprovedLedgerMappingPort mappings;
    private final SecurityContextPort security;
    private final PolicyEvaluationPort policies;
    private final ReactiveOAuth2AuthorizedClientManager clients;
    private final ReactiveClientRegistrationRepository registrations;
    private final List<GlLedgerObserverProfile> profiles;
    private final Map<String, ReactiveJwtDecoder> decoders;
    private final WebClient transport;
    private final Clock clock;
    private final LongSupplier nanos;

    public SdkLedgerPostingSourcePortImpl(GlApprovedLedgerMappingPort mappings, SecurityContextPort security,
            PolicyEvaluationPort policies, ReactiveOAuth2AuthorizedClientManager clients, ReactiveClientRegistrationRepository registrations,
            List<GlLedgerObserverProfile> profiles, Map<String, ReactiveJwtDecoder> decoders, WebClient transport, Clock clock, LongSupplier nanos) {
        this.mappings=Objects.requireNonNull(mappings);this.security=Objects.requireNonNull(security);this.policies=Objects.requireNonNull(policies);
        this.clients=Objects.requireNonNull(clients);this.registrations=Objects.requireNonNull(registrations);this.profiles=List.copyOf(profiles);
        this.decoders=Map.copyOf(decoders);this.transport=Objects.requireNonNull(transport);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
        if (profiles.isEmpty() || profiles.stream().map(GlLedgerObserverProfile::resourceTenantId).distinct().count()!=profiles.size()
                || profiles.stream().map(GlLedgerObserverProfile::registrationId).distinct().count()!=profiles.size()
                || profiles.stream().anyMatch(profile->!this.decoders.containsKey(profile.registrationId())))
            throw new IllegalArgumentException("Unique tenant-bound Ledger observer registrations required");
    }

    @Override public Mono<GlPostingSourceObservation> observe(GlAccountingAuthority.Admission admission, GlPostingRules.Validated posting) {
        return Mono.defer(() -> {
        var stage=new AtomicReference<>(Stage.ADMISSION);
        return GlAccountingBoundary.outsideTransaction(() -> Mono.defer(() -> {
            Objects.requireNonNull(admission);Objects.requireNonNull(posting);admission.requireCurrent();
            String tenant=admission.intent().tenantId(),book=admission.intent().bookId();
            if (!"post".equals(admission.intent().operation()) || !posting.intentDigest().equals(admission.intent().intentDigest())
                    || !posting.canonicalIntent().equals(admission.intent().canonicalIntent())) throw denied();
            var source=posting.request().source();
            if (!"LEDGER".equals(source.owner()) || !"COMMIT".equals(source.eventKind()) || source.revision()!=1L || source.effectOrdinal()!=0) throw denied();
            GlPostingRules.uuid(source.objectReference());GlPostingRules.uuid(source.effectReference());
            GlPostingRules.uuid(source.financialReceiptReference());GlPostingRules.uuid(source.provenanceReference());
            var selected=new ReceiptSelection(tenant,UUID.fromString(source.objectReference()),UUID.fromString(source.effectReference()),
                    UUID.fromString(source.provenanceReference()),UUID.fromString(source.financialReceiptReference()),source.digest());
            var profile=profiles.stream().filter(value->value.resourceTenantId().equals(tenant)).findFirst().orElseThrow(SdkLedgerPostingSourcePortImpl::denied);
            Instant started=clock.instant();long before=nanos.getAsLong();
            var originalUntil=Instant.ofEpochMilli(Math.min(admission.validUntilEpochMillis(),started.plusSeconds(5).toEpochMilli()));
            var budget=R2dbcOperationBudget.observed(started,originalUntil,
                    before,Duration.ofSeconds(5),clock,nanos);
            return current(admission,budget).then(Mono.defer(()->{stage.set(Stage.MAPPING_INITIAL);return mappings.current(admission,posting);})).flatMap(mapping -> {
                budget.remaining();GlLedgerMappingSnapshotRules.matches(tenant,book,mapping.snapshot(),posting);
                if (!mapping.approvalId().toString().equals(admission.approval().approvalReference())
                        || !mapping.approvalProofDigest().equals(admission.approval().approvalDigest()) || mapping.validUntilEpochMillis()<=clock.millis()) throw denied();
                var snapshot=mapping.snapshot().snapshot();var accounts=Set.of(snapshot.source().ledger().accountId(),snapshot.destination().ledger().accountId());
                stage.set(Stage.POLICY_INITIAL);
                return authorize(admission,posting,mapping,budget).flatMap(initial -> Mono.defer(()->{stage.set(Stage.OBSERVER_CREDENTIAL);return credential(profile,accounts,budget);}).flatMap(credential -> {
                    var until=earliest(originalUntil,earliest(Instant.ofEpochMilli(mapping.validUntilEpochMillis()),credential.until()));
                    var outgoing=budget.intersect(R2dbcOperationBudget.observed(started,until,before,Duration.ofSeconds(5),clock,nanos));
                    var apiClient=new ApiClient(transport.mutate().codecs(codecs->codecs.defaultCodecs().maxInMemorySize(1024*1024)).build())
                            .setBasePath(profile.baseUri().toString().replaceAll("/$",""));
                    apiClient.addDefaultHeader(HttpHeaders.AUTHORIZATION,"Bearer "+credential.value());
                    stage.set(Stage.LEDGER_HTTP);
                    return new LedgerFundsApi(apiClient).getFundsMonetaryReceipts(tenant,selected.reservationId(),null)
                        .switchIfEmpty(Mono.error(unavailable())).timeout(outgoing.remaining()).map(value -> {stage.set(Stage.RECEIPT_VALIDATION);return verify(selected,posting,mapping,value);})
                        .flatMap(ignored -> Mono.defer(()->{stage.set(Stage.MAPPING_CURRENT);return mappings.current(admission,posting);}).filter(again->again.approvalId().equals(mapping.approvalId())
                                &&again.snapshotId().equals(mapping.snapshotId())&&again.snapshot().snapshotSha256().equals(mapping.snapshot().snapshotSha256())
                                &&again.approvalProofDigest().equals(mapping.approvalProofDigest())&&again.validUntilEpochMillis()>clock.millis())
                            .switchIfEmpty(Mono.error(denied())).flatMap(again->Mono.defer(()->{stage.set(Stage.POLICY_CURRENT);return current(admission,outgoing).then(authorize(admission,posting,mapping,outgoing));})
                            .filter(value->value.policyRevision().equals(initial.policyRevision())).switchIfEmpty(Mono.error(denied()))
                            .then(Mono.fromSupplier(() -> {
                                outgoing.remaining();admission.requireCurrent();
                                return new GlPostingSourceObservation(tenant,book,posting.request().actionId(),posting.intentDigest(),posting.sourceClaimKey(),
                                        admission.actor().issuer(),admission.actor().subject(),Math.min(until.toEpochMilli(),again.validUntilEpochMillis()));
                            }))));
                }));
            }).timeout(budget.remaining());
        })).onErrorMap(error->mapFailure(stage.get(),error));
        });
    }

    private Mono<PolicyEvaluationProvenance> authorize(GlAccountingAuthority.Admission admission,GlPostingRules.Validated posting,
            GlApprovedLedgerMappingPort.Approved mapping,R2dbcOperationBudget budget) {
        budget.remaining();return policies.evaluate(admission.actor(),"accounting.gl.source.ledger.observe","accounting/book/"+admission.intent().bookId(),
                Map.of("tenantId",admission.intent().tenantId(),"bookId",admission.intent().bookId(),"actionId",posting.request().actionId(),
                        "canonicalIntent",posting.canonicalIntent(),"intentDigest",posting.intentDigest(),"sourceClaimKey",posting.sourceClaimKey(),
                        "mappingSnapshotId",mapping.snapshotId().toString(),"canonicalMappingSnapshot",mapping.snapshot().canonical(),"mappingSnapshotSha256",mapping.snapshot().snapshotSha256()))
                .filter(result->result.decision().granted()&&result.decision().obligations().isEmpty()&&result.provenance().isPresent())
                .switchIfEmpty(Mono.error(denied())).map(result->{budget.remaining();return result.provenance().orElseThrow();}).timeout(budget.remaining());
    }

    private Mono<Void> current(GlAccountingAuthority.Admission admission,R2dbcOperationBudget budget) {
        budget.remaining();return security.currentPrincipal().filter(admission.actor()::equals).switchIfEmpty(Mono.error(denied()))
                .doOnNext(actor->{GlAccountingAuthority.requireCurrent(actor);budget.remaining();}).then().timeout(budget.remaining());
    }

    private Mono<Credential> credential(GlLedgerObserverProfile profile,Set<UUID> accounts,R2dbcOperationBudget budget) {
        return registrations.findByRegistrationId(profile.registrationId()).filter(registration->AuthorizationGrantType.CLIENT_CREDENTIALS.equals(registration.getAuthorizationGrantType())
                && profile.clientId().equals(registration.getClientId()) && profile.tokenUri().toString().equals(registration.getProviderDetails().getTokenUri()))
            .switchIfEmpty(Mono.error(denied())).flatMap(registration->clients.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(profile.registrationId())
                .principal("gl-ledger-observer/"+profile.registrationId()).build()).filter(client->registration.equals(client.getClientRegistration())).switchIfEmpty(Mono.error(denied())))
            .flatMap(client->{var token=client.getAccessToken();
                if(token==null||!OAuth2AccessToken.TokenType.BEARER.equals(token.getTokenType())||token.getExpiresAt()==null||!token.getExpiresAt().isAfter(clock.instant()))return Mono.error(denied());
                return decoders.get(profile.registrationId()).decode(token.getTokenValue()).map(jwt->{requireGrant(profile,jwt,accounts,clock.instant());budget.remaining();
                    return new Credential(token.getTokenValue(),earliest(token.getExpiresAt(),jwt.getExpiresAt()));});
            }).timeout(budget.remaining());
    }

    static void requireGrant(GlLedgerObserverProfile profile,Jwt jwt,Set<UUID> accounts,Instant now) {
        if(!profile.issuer().equals(jwt.getClaimAsString("iss"))||!profile.subject().equals(jwt.getSubject())||!jwt.getAudience().contains(profile.audience())
                ||!profile.resourceTenantId().equals(jwt.getClaims().get(profile.tenantClaim()))||!profile.clientId().equals(jwt.getClaims().get(profile.clientClaim()))
                ||!profile.machineValue().equals(jwt.getClaims().get(profile.kindClaim()))||jwt.getClaims().containsKey(profile.staffClaim())
                ||jwt.getExpiresAt()==null||!jwt.getExpiresAt().isAfter(now)||jwt.getNotBefore()!=null&&jwt.getNotBefore().isAfter(now))throw denied();
        Object scope=jwt.getClaims().get(profile.scopeClaim()),grants=jwt.getClaims().get(profile.accountsClaim());
        if(!(scope instanceof String text)||text.length()>4096||text.chars().anyMatch(c->c<0x20||c>0x7e||c==0x22||c==0x5c)
                ||Arrays.stream(text.split(" ",-1)).anyMatch(String::isEmpty)||!new HashSet<>(Arrays.asList(text.split(" "))).contains("funds:read")
                ||!(grants instanceof List<?> list)||list.isEmpty()||list.size()>1000)throw denied();
        var actual=new HashSet<UUID>();for(var value:list){
            if(!(value instanceof String id)||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw denied();
            var parsed=UUID.fromString(id);if(parsed.equals(new UUID(0,0)))throw denied();actual.add(parsed);
        }
        if(accounts.size()!=2||!actual.containsAll(accounts))throw denied();
    }

    private static MappedCommit verify(ReceiptSelection selected,GlPostingRules.Validated posting,
            GlApprovedLedgerMappingPort.Approved mapping,io.fireflysoftware.core.banking.ledger.sdk.model.EvidenceView value) {
        if(value==null||value.getEvidenceStatus()!=io.fireflysoftware.core.banking.ledger.sdk.model.EvidenceView.EvidenceStatusEnum.RETAINED)throw unavailable();
        var current=Objects.requireNonNull(value.getCurrentReservation());var snapshot=mapping.snapshot().snapshot();
        if(!selected.reservationId().equals(current.getReservationId())||!snapshot.source().ledger().accountId().equals(current.getSourceAccountId())
                ||!snapshot.destination().ledger().accountId().equals(current.getDestinationAccountId())||current.getVersion()==null||current.getVersion()<0)throw denied();
        var evidence=new EvidenceView(EvidenceStatus.RETAINED,accepted(value.getAcceptedReserve()),monetary(value.getCommit()),
                value.getReversal()==null?null:monetary(value.getReversal()),null);
        var verified=GlLedgerSourceRules.verifyCommit(evidence,selected);var mapped=GlLedgerMappingRules.mapCommit(verified,snapshot);
        if(posting.request().sourceOccurrenceEpochMillis()!=verified.identity().occurredAtEpochMillis()||posting.request().entries().size()!=1
                ||!posting.request().entries().getFirst().lines().equals(mapped.lines()))throw denied();
        return mapped;
    }

    private static CanonicalForm canonical(io.fireflysoftware.core.banking.ledger.sdk.model.CanonicalForm value){Objects.requireNonNull(value);return new CanonicalForm(value.getEncoding(),value.getCanonical(),value.getDigest());}
    private static AcceptedReserveEnvelope accepted(io.fireflysoftware.core.banking.ledger.sdk.model.AcceptedReserveEnvelope value){Objects.requireNonNull(value);var envelope=new AcceptedReserveEnvelope(accepted(value.getValue()),canonical(value.getCanonical()));FundsReceiptCodec.verify(envelope);return envelope;}
    private static AcceptedReserve accepted(io.fireflysoftware.core.banking.ledger.sdk.model.AcceptedReserve value){Objects.requireNonNull(value);var r=Objects.requireNonNull(value.getIntent());return new AcceptedReserve(value.getTenant(),value.getReservationId(),new FundsContracts.Reserve(r.getOperationId(),r.getSourceAccountId(),r.getDestinationAccountId(),r.getAmount(),r.getCurrency(),r.getTtlSeconds(),r.getNotAfterEpochMillis()),value.getIntentRecordedAtEpochMillis(),value.getExpiresAtEpochMillis(),snapshot(value.getSource()),snapshot(value.getDestination()));}
    private static EnrollmentSnapshot snapshot(io.fireflysoftware.core.banking.ledger.sdk.model.EnrollmentSnapshot value){Objects.requireNonNull(value);var kind=switch(Objects.requireNonNull(value.getKind())){case CUSTOMER->FundsContracts.Kind.CUSTOMER;case BANK_CONTROL->FundsContracts.Kind.BANK_CONTROL;};return new EnrollmentSnapshot(value.getAccountId(),value.getEnrollmentId(),value.getContractId(),value.getCurrency(),value.getCurrencyScale(),value.getCurrencyPolicy(),kind);}
    private static BalanceVector balances(io.fireflysoftware.core.banking.ledger.sdk.model.BalanceVector value){Objects.requireNonNull(value);return new BalanceVector(value.getBookedBefore(),value.getHeldBefore(),value.getBookedAfter(),value.getHeldAfter());}
    private static MonetaryReceiptEnvelope monetary(io.fireflysoftware.core.banking.ledger.sdk.model.MonetaryReceiptEnvelope value){Objects.requireNonNull(value);var r=Objects.requireNonNull(value.getValue());var e=Objects.requireNonNull(r.getEffect());var actor=Objects.requireNonNull(r.getActor());var kind=switch(Objects.requireNonNull(r.getEffectKind())){case COMMIT->EffectKind.COMMIT;case REVERSE->EffectKind.REVERSE;};var effect=new MoneyEffect(e.getAmount(),e.getCurrency(),snapshot(e.getSource()),snapshot(e.getDestination()),e.getSourceMinorUnits(),e.getDestinationMinorUnits(),e.getFromAccountId(),e.getToAccountId(),balances(e.getSourceBalances()),balances(e.getDestinationBalances()));var receipt=new MonetaryReceipt(r.getReceiptId(),r.getEventId(),r.getTenant(),r.getReservationId(),r.getOperationId(),kind,r.getActionId(),accepted(r.getAcceptedReserve()),r.getPostingId(),r.getOriginalCommitReceiptId(),r.getOriginalPostingId(),r.getEffectRecordedAtEpochMillis(),effect,new Actor(actor.getIssuer(),actor.getSubject()));var envelope=new MonetaryReceiptEnvelope(receipt,canonical(value.getCanonical()));FundsReceiptCodec.verify(envelope);return envelope;}
    enum Stage { ADMISSION, MAPPING_INITIAL, POLICY_INITIAL, OBSERVER_CREDENTIAL, LEDGER_HTTP, RECEIPT_VALIDATION, MAPPING_CURRENT, POLICY_CURRENT }
    static Throwable mapFailure(Stage stage,Throwable error) {
        LOG.warn("Ledger source observation failed: {}",failureDescription(stage,error));
        return error instanceof ResponseStatusException?error:unavailable();
    }
    static String failureDescription(Stage stage,Throwable error) {
        String category=error instanceof org.springframework.security.oauth2.core.OAuth2AuthorizationException?"OAUTH_REFUSED":
                error instanceof org.springframework.security.oauth2.jwt.JwtException?"JWT_REFUSED":
                error instanceof org.springframework.web.reactive.function.client.WebClientResponseException?"HTTP_REFUSED":
                error instanceof java.util.concurrent.TimeoutException?"TIMEOUT":
                error instanceof ResponseStatusException?"OWNER_REFUSED":"LOCAL_FAILURE";
        int status=error instanceof org.springframework.web.reactive.function.client.WebClientResponseException response?response.getStatusCode().value():
                error instanceof ResponseStatusException response?response.getStatusCode().value():0;
        return "stage="+stage.name()+" category="+category+" exception="+error.getClass().getName()+" status="+status;
    }
    private static Instant earliest(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private record Credential(String value,Instant until){@Override public String toString(){return "Credential[redacted]";}}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Current exact Ledger source and full approved mapping required");}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Original Ledger monetary evidence unavailable");}
}
