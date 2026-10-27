/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.core.accounting.core.services.posting.impl;

import com.fasterxml.jackson.databind.json.JsonMapper;

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.*;
import io.fireflysoftware.core.accounting.models.entities.posting.*;
import io.fireflysoftware.core.accounting.models.repositories.posting.GlNativePostingRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

@Service
public final class GlPostingApplicationServiceImpl implements GlPostingApplicationService {
    private final GlAccountingAuthority authority;
    private final ObjectProvider<GlNativePostingRepository> repositories;
    private final ObjectProvider<GlAccountingTransaction> transactions;
    private final ObjectProvider<AccountingCurrencyObservationPort> currencies;
    private final ObjectProvider<AccountingPostingSourcePort> sources;
    private final Environment environment;

    public GlPostingApplicationServiceImpl(
            GlAccountingAuthority authority,
            @Qualifier("glNativePostingRepository")
                    ObjectProvider<GlNativePostingRepository> repositories,
            @Qualifier("glNativePostingTransaction")
                    ObjectProvider<GlAccountingTransaction> transactions,
            ObjectProvider<AccountingCurrencyObservationPort> currencies,
            ObjectProvider<AccountingPostingSourcePort> sources,
            Environment environment) {
        this.authority = authority;
        this.repositories = repositories;
        this.transactions = transactions;
        this.currencies = currencies;
        this.sources = sources;
        this.environment = environment;
    }

    @Override
    public Mono<GlPostingReceipt> post(String tenant, String book, byte[] original) {
        if (original == null || original.length == 0 || original.length > 1048576)
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Bounded original posting request required"));
        byte[] frozen = original.clone();
        return Mono.defer(
                () -> {
                    enabled();
                    var decoded = GlPostingIntentCodec.decode(tenant, book, frozen);
                    var repository = required(repositories);
                    var transaction = required(transactions);
                    return existing(tenant, book, decoded.validated(), repository, transaction)
                            .flatMap(
                                    retained ->
                                            retained.isPresent()
                                                    ? Mono.just(retained.get())
                                                    : postNew(
                                                            tenant,
                                                            book,
                                                            decoded,
                                                            repository,
                                                            transaction))
                            .onErrorMap(DuplicateKeyException.class, error -> conflict());
                });
    }

    private Mono<GlPostingReceipt> postNew(
            String tenant,
            String book,
            GlPostingIntentCodec.Decoded decoded,
            GlNativePostingRepository repository,
            GlAccountingTransaction transaction) {
        var currency = required(currencies);
        var source = required(sources);
        var validated = decoded.validated();
        var intent =
                new AccountingApprovalIntent(
                        tenant,
                        book,
                        "post",
                        validated.intentDigest(),
                        validated.canonicalIntent(),
                        Map.of("actionId", validated.request().actionId()));
        return authority
                .authorizeAccounts(intent, accounts(validated), true)
                .flatMap(
                        admission ->
                                source.observe(admission, validated)
                                        .switchIfEmpty(Mono.error(unavailable()))
                                        .timeout(Duration.ofSeconds(4))
                                        .flatMap(
                                                observation -> {
                                                    source(admission, validated, observation);
                                                    return observations(validated, currency)
                                                            .flatMap(
                                                                    observed ->
                                                                            commitNew(
                                                                                    tenant,
                                                                                    book,
                                                                                    decoded,
                                                                                    admission,
                                                                                    observation,
                                                                                    observed,
                                                                                    repository,
                                                                                    transaction));
                                                }));
    }

    private Mono<GlPostingReceipt> commitNew(
            String tenant,
            String book,
            GlPostingIntentCodec.Decoded decoded,
            GlAccountingAuthority.Admission admission,
            GlPostingSourceObservation source,
            Map<String, GlObservedCurrency> observed,
            GlNativePostingRepository repository,
            GlAccountingTransaction transaction) {
        long until =
                Math.min(
                        admission.validUntilEpochMillis(),
                        Math.min(
                                decoded.validated().request().notAfterEpochMillis(),
                                source.validUntilEpochMillis()));
        for (var value : observed.values()) until = Math.min(until, currencyUntil(value));
        var bounded =
                new GlAccountingAuthority.Admission(
                        admission.actor(),
                        admission.approval(),
                        until,
                        admission.intent(),
                        admission.accountIds());
        bounded.requireCurrent();
        return transaction
                .executeWitnessed(
                        bounded,
                        operation ->
                                controls(tenant, book, decoded.validated(), observed, repository)
                                        .then(
                                                Mono.defer(
                                                        () -> {
                                                            operation.requireCurrent();
                                                            return write(
                                                                    tenant,
                                                                    book,
                                                                    decoded,
                                                                    operation.admission(),
                                                                    repository);
                                                        })))
                .onErrorMap(GlAccountingTransaction.CompletionKnown.class,
                        error -> new GlPostingCommitKnownException(uuid(decoded.validated().request().actionId())))
                .onErrorMap(
                        GlAccountingTransaction.CompletionUncertain.class,
                        error ->
                                new GlPostingOutcomeUncertainException(
                                        uuid(decoded.validated().request().actionId())))
                .flatMap(
                        completed ->
                                finalDelivery(tenant, book, decoded.validated(), completed)
                                        .onErrorMap(
                                                error ->
                                                        new GlPostingCommitKnownException(
                                                                uuid(
                                                                        decoded.validated()
                                                                                .request()
                                                                                .actionId()))));
    }

    @Override
    public Mono<GlPostingReceipt> recover(String tenant, String book, byte[] original) {
        if (original == null || original.length == 0 || original.length > 1048576)
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Bounded original posting request required"));
        byte[] frozen = original.clone();
        return Mono.defer(
                () -> {
                    enabled();
                    var v = GlPostingIntentCodec.decode(tenant, book, frozen).validated();
                    return existing(tenant, book, v, required(repositories), required(transactions))
                            .flatMap(
                                    value ->
                                            value.<Mono<GlPostingReceipt>>map(Mono::just)
                                                    .orElseGet(
                                                            () ->
                                                                    Mono.error(
                                                                            new ResponseStatusException(
                                                                                    HttpStatus
                                                                                            .NOT_FOUND,
                                                                                    "No retained"
                                                                                        + " scoped"
                                                                                        + " posting"))));
                });
    }

    private AccountingApprovalIntent readIntent(
            String tenant, String book, GlPostingRules.Validated validated) {
        var canonical =
                GlPostingRules.canonical(
                        "GL_READ_CONTEXT_V1",
                        List.of(
                                tenant,
                                book,
                                "read",
                                validated.request().actionId(),
                                validated.intentDigest()));
        return new AccountingApprovalIntent(
                tenant,
                book,
                "read",
                GlPostingRules.digest(canonical),
                canonical,
                Map.of("actionId", validated.request().actionId()));
    }

    private <T> Mono<T> finalDelivery(
            String tenant,
            String book,
            GlPostingRules.Validated validated,
            GlAccountingTransaction.Completed<T> completed) {
        return completed
                .operation()
                .bound(
                        currentDelivery(tenant, book, validated, completed.operation())
                                .then(
                                        Mono.fromSupplier(
                                                () -> {
                                                    completed.operation().requireCurrent();
                                                    return completed.value();
                                                })));
    }

    private Mono<Void> currentDelivery(
            String tenant,
            String book,
            GlPostingRules.Validated validated,
            GlNativeOperation operation) {
        return Mono.defer(
                () -> {
                    operation.requireCurrent();
                    return authority
                            .authorizeAccounts(
                                    readIntent(tenant, book, validated), accounts(validated), false)
                            .doOnNext(
                                    current -> {
                                        if (!current.actor()
                                                        .issuer()
                                                        .equals(
                                                                operation
                                                                        .admission()
                                                                        .actor()
                                                                        .issuer())
                                                || !current.actor()
                                                        .subject()
                                                        .equals(
                                                                operation
                                                                        .admission()
                                                                        .actor()
                                                                        .subject())) throw denied();
                                        current.requireCurrent();
                                        operation.requireCurrent();
                                    })
                            .then();
                });
    }

    private Mono<Optional<GlPostingReceipt>> existing(
            String tenant,
            String book,
            GlPostingRules.Validated validated,
            GlNativePostingRepository repository,
            GlAccountingTransaction transaction) {
        return authority
                .authorizeAccounts(readIntent(tenant, book, validated), accounts(validated), false)
                .flatMap(
                        admission ->
                                transaction.executeWitnessed(
                                        admission,
                                        operation ->
                                                repository
                                                        .findAction(
                                                                uuid(tenant),
                                                                uuid(book),
                                                                uuid(
                                                                        validated
                                                                                .request()
                                                                                .actionId()))
                                                        .flatMap(
                                                                action ->
                                                                        retainedReceipt(
                                                                                tenant,
                                                                                book,
                                                                                validated,
                                                                                repository,
                                                                                action))
                                                        .map(Optional::of)
                                                        .defaultIfEmpty(Optional.empty())))
                .onErrorMap(GlAccountingTransaction.CompletionKnown.class, error -> unavailable())
                .onErrorMap(
                        GlAccountingTransaction.CompletionUncertain.class, error -> unavailable())
                .flatMap(completed -> finalDelivery(tenant, book, validated, completed))
                .onErrorMap(GlNativeTimeWindow.Expired.class, error -> denied())
                .onErrorMap(java.util.concurrent.TimeoutException.class, error -> denied());
    }

    private Mono<GlPostingReceipt> retainedReceipt(
            String tenant,
            String book,
            GlPostingRules.Validated validated,
            GlNativePostingRepository repository,
            GlNativeJournalAction action) {
        requireRetainedAction(tenant, book, validated, action);
        return repository
                .findPosting(uuid(tenant), uuid(book), action.actionId())
                .switchIfEmpty(Mono.error(unavailable()))
                .map(
                        posting -> {
                            requireRetainedPosting(validated.request(), action, posting);
                            return new GlPostingReceipt(
                                    action.receiptId(),
                                    action.actionId(),
                                    posting.id(),
                                    posting.journalSequence(),
                                    action.intentDigest(),
                                    true,
                                    action.recordedAt());
                        });
    }

    private static void requireRetainedAction(
            String tenant,
            String book,
            GlPostingRules.Validated validated,
            GlNativeJournalAction action) {
        if (action.recordedAt() == null
                || !GlPostingIntentCodec.SHAPE.equals(action.requestShape())
                || action.originalRequest() == null) throw unavailable();
        try {
            var retained =
                    GlPostingIntentCodec.decode(tenant, book, action.originalRequest()).validated();
            if (!retained.canonicalIntent().equals(action.canonicalIntent())
                    || action.notAfterEpochMillis() != validated.request().notAfterEpochMillis())
                throw unavailable();
        } catch (RuntimeException error) {
            throw unavailable();
        }
        if (!action.tenantId().equals(uuid(tenant))
                || !action.bookId().equals(uuid(book))
                || !action.actionId().equals(uuid(validated.request().actionId()))
                || !"POST".equals(action.kind())
                || !action.intentDigest().equals(validated.intentDigest())
                || !action.canonicalIntent().equals(validated.canonicalIntent())
                || !action.economicDigest().equals(validated.economicIntentDigest())
                || !action.canonicalEconomicIntent().equals(validated.canonicalEconomicIntent())
                || !action.sourceClaimKey().equals(validated.sourceClaimKey())
                || !Arrays.equals(
                        action.accountIds(),
                        accounts(validated).stream().map(UUID::fromString).toArray(UUID[]::new)))
            throw conflict();
    }

    private static void requireRetainedPosting(
            GlPostingRequest request, GlNativeJournalAction action, GlNativePosting posting) {
        if (!posting.id().equals(action.postingId())
                || !posting.actionId().equals(action.actionId())
                || !posting.tenantId().equals(action.tenantId())
                || !posting.bookId().equals(action.bookId())
                || !"POST".equals(posting.kind())
                || posting.journalSequence() <= 0
                || posting.originalPostingId() != null
                || posting.expectedBookVersion() != request.expectedBookVersion()
                || !posting.periodId().equals(uuid(request.periodId()))
                || posting.expectedPeriodVersion() != request.expectedPeriodVersion()
                || !posting.businessDate().equals(LocalDate.parse(request.businessDate()))
                || !posting.mappingId().equals(uuid(request.mappingId()))
                || posting.mappingVersion() != request.mappingVersion()
                || !posting.mappingDigest().equals(request.mappingDigest())) throw unavailable();
    }

    private Mono<Map<String, GlObservedCurrency>> observations(
            GlPostingRules.Validated v, AccountingCurrencyObservationPort port) {
        var references = new TreeMap<String, GlCurrencyReference>();
        v.request()
                .entries()
                .forEach(
                        e ->
                                e.lines()
                                        .forEach(
                                                l ->
                                                        references.put(
                                                                l.currencyAdmissionId(),
                                                                l.currency())));
        return Flux.fromIterable(references.entrySet())
                .concatMap(
                        e ->
                                port.observe(e.getValue())
                                        .switchIfEmpty(Mono.error(unavailable()))
                                        .map(
                                                value -> {
                                                    if (!e.getValue().equals(value.reference())
                                                            || !e.getValue()
                                                                    .digest()
                                                                    .equals(
                                                                            GlPostingRules.digest(
                                                                                    value
                                                                                            .canonicalEncoding())))
                                                        throw conflict();
                                                    currencyUntil(value);
                                                    return Map.entry(e.getKey(), value);
                                                }))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Mono<Void> controls(
            String tenant,
            String book,
            GlPostingRules.Validated validated,
            Map<String, GlObservedCurrency> observed,
            GlNativePostingRepository repository) {
        var tenantId = uuid(tenant);
        var bookId = uuid(book);
        var request = validated.request();
        var date = LocalDate.parse(request.businessDate());
        return repository
                .findBook(tenantId, bookId)
                .filter(
                        row ->
                                row.tenantId().equals(tenantId)
                                        && row.id().equals(bookId)
                                        && row.lifecycle().equals("ACTIVE")
                                        && row.controlVersion() == request.expectedBookVersion())
                .switchIfEmpty(Mono.error(conflict()))
                .then(
                        repository
                                .findPeriod(tenantId, bookId, uuid(request.periodId()))
                                .filter(
                                        row ->
                                                row.tenantId().equals(tenantId)
                                                        && row.bookId().equals(bookId)
                                                        && row.id().equals(uuid(request.periodId()))
                                                        && row.lifecycle().equals("OPEN")
                                                        && row.controlVersion()
                                                                == request.expectedPeriodVersion()
                                                        && !date.isBefore(row.fromDate())
                                                        && date.isBefore(row.toDate()))
                                .switchIfEmpty(Mono.error(conflict())))
                .then(
                        repository
                                .findMapping(tenantId, bookId, uuid(request.mappingId()))
                                .filter(
                                        row ->
                                                row.tenantId().equals(tenantId)
                                                        && row.bookId().equals(bookId)
                                                        && row.id()
                                                                .equals(uuid(request.mappingId()))
                                                        && row.revision()
                                                                == request.mappingVersion()
                                                        && row.definitionDigest()
                                                                .equals(request.mappingDigest()))
                                .switchIfEmpty(Mono.error(conflict())))
                .thenMany(
                        Flux.fromIterable(request.entries())
                                .concatMap(
                                        entry ->
                                                Flux.fromIterable(entry.lines())
                                                        .concatMap(
                                                                line ->
                                                                        controlLine(
                                                                                tenantId,
                                                                                bookId,
                                                                                line,
                                                                                observed,
                                                                                repository))))
                .then();
    }

    private Mono<GlNativeCurrencyAdmission> controlLine(
            UUID tenant,
            UUID book,
            GlPostingLine line,
            Map<String, GlObservedCurrency> observed,
            GlNativePostingRepository repository) {
        return repository
                .findAccount(tenant, book, uuid(line.accountId()))
                .filter(
                        row ->
                                row.tenantId().equals(tenant)
                                        && row.bookId().equals(book)
                                        && row.accountId().equals(uuid(line.accountId()))
                                        && row.kind().equals("POSTING")
                                        && row.lifecycle().equals("ACTIVE")
                                        && row.controlVersion() == line.expectedAccountVersion()
                                        && row.currencyAdmissionId()
                                                .equals(uuid(line.currencyAdmissionId())))
                .switchIfEmpty(Mono.error(conflict()))
                .then(
                        repository
                                .findCurrency(tenant, book, uuid(line.currencyAdmissionId()))
                                .filter(
                                        row ->
                                                row.tenantId().equals(tenant)
                                                        && row.bookId().equals(book)
                                                        && row.id()
                                                                .equals(
                                                                        uuid(
                                                                                line
                                                                                        .currencyAdmissionId()))
                                                        && row.lifecycle().equals("ACTIVE")
                                                        && row.controlVersion()
                                                                == line.expectedCurrencyVersion()
                                                        && row.postedScale() == line.postedScale()
                                                        && row.policyId()
                                                                .equals(
                                                                        uuid(
                                                                                line.currency()
                                                                                        .policyId()))
                                                        && row.currencyId()
                                                                .equals(
                                                                        uuid(
                                                                                line.currency()
                                                                                        .currencyId()))
                                                        && row.isoCode()
                                                                .equals(line.currency().isoCode())
                                                        && row.policyVersion()
                                                                == line.currency().version()
                                                        && row.policyDigest()
                                                                .equals(line.currency().digest())
                                                        && row.policyEncoding()
                                                                .equals(
                                                                        line.currency()
                                                                                .encodingVersion())
                                                        && row.canonicalPolicy()
                                                                .equals(
                                                                        observed.get(
                                                                                        line
                                                                                                .currencyAdmissionId())
                                                                                .canonicalEncoding())
                                                        && row.postedScale()
                                                                == observed.get(
                                                                                line
                                                                                        .currencyAdmissionId())
                                                                        .postedScale())
                                .switchIfEmpty(Mono.error(conflict())));
    }

    private Mono<GlPostingReceipt> write(
            String tenant,
            String book,
            GlPostingIntentCodec.Decoded decoded,
            GlAccountingAuthority.Admission admission,
            GlNativePostingRepository repository) {
        admission.requireCurrent();
        var validated = decoded.validated();
        var request = validated.request();
        var tenantId = uuid(tenant);
        var bookId = uuid(book);
        var actionId = uuid(request.actionId());
        var receiptId = UUID.randomUUID();
        var postingId = UUID.randomUUID();
        var proposed =
                new GlNativeJournalAction(
                        actionId,
                        tenantId,
                        bookId,
                        "POST",
                        validated.intentDigest(),
                        validated.canonicalIntent(),
                        validated.economicIntentDigest(),
                        validated.canonicalEconomicIntent(),
                        validated.sourceClaimKey(),
                        accounts(validated).stream().map(UUID::fromString).toArray(UUID[]::new),
                        request.notAfterEpochMillis(),
                        receiptId,
                        admission.actor().issuer(),
                        admission.actor().subject(),
                        admission.validUntilEpochMillis(),
                        postingId,
                        GlPostingIntentCodec.SHAPE,
                        decoded.originalJson());
        return repository
                .appendAction(proposed)
                .switchIfEmpty(Mono.error(unavailable()))
                .then(
                        Mono.defer(
                                () ->
                                        repository
                                                .findAction(tenantId, bookId, actionId)
                                                .switchIfEmpty(Mono.error(unavailable()))))
                .flatMap(
                        saved -> {
                            requireRetainedAction(tenant, book, validated, saved);
                            if (!saved.receiptId().equals(receiptId)
                                    || !saved.postingId().equals(postingId)
                                    || saved.authorityUntilEpochMillis()
                                            != admission.validUntilEpochMillis()
                                    || !saved.actorIssuer().equals(admission.actor().issuer())
                                    || !saved.actorSubject().equals(admission.actor().subject()))
                                throw unavailable();
                            return repository
                                    .findSequence(tenantId, bookId)
                                    .switchIfEmpty(Mono.error(unavailable()))
                                    .flatMap(
                                            sequence ->
                                                    repository.advanceSequence(
                                                            tenantId,
                                                            bookId,
                                                            sequence.lastSequence()))
                                    .flatMap(
                                            sequence ->
                                                    appendJournal(
                                                                    repository,
                                                                    saved,
                                                                    request,
                                                                    sequence)
                                                            .then(
                                                                    Mono.fromSupplier(
                                                                            () -> {
                                                                                admission
                                                                                        .requireCurrent();
                                                                                return new GlPostingReceipt(
                                                                                        receiptId,
                                                                                        actionId,
                                                                                        postingId,
                                                                                        sequence,
                                                                                        validated
                                                                                                .intentDigest(),
                                                                                        false,
                                                                                        saved
                                                                                                .recordedAt());
                                                                            })));
                        });
    }

    private Mono<Void> appendJournal(
            GlNativePostingRepository repository,
            GlNativeJournalAction action,
            GlPostingRequest request,
            long sequence) {
        var posting =
                new GlNativePosting(
                        action.postingId(),
                        action.tenantId(),
                        action.bookId(),
                        action.actionId(),
                        "POST",
                        sequence,
                        request.expectedBookVersion(),
                        uuid(request.periodId()),
                        request.expectedPeriodVersion(),
                        LocalDate.parse(request.businessDate()),
                        uuid(request.mappingId()),
                        request.mappingVersion(),
                        request.mappingDigest(),
                        null);
        return repository
                .appendPosting(posting)
                .switchIfEmpty(Mono.error(unavailable()))
                .thenMany(
                        Flux.fromIterable(request.entries())
                                .concatMap(entry -> appendEntry(repository, action, entry)))
                .thenMany(
                        Flux.fromIterable(List.of("AUDIT", "OUTBOX"))
                                .concatMap(
                                        channel ->
                                                repository
                                                        .appendEvent(
                                                                event(
                                                                        action.tenantId(),
                                                                        action.bookId(),
                                                                        action.actionId(),
                                                                        action.receiptId(),
                                                                        action.intentDigest(),
                                                                        channel))
                                                        .switchIfEmpty(Mono.error(unavailable()))))
                .then();
    }

    private Mono<Void> appendEntry(
            GlNativePostingRepository repository,
            GlNativeJournalAction action,
            GlPostingEntry entry) {
        var row =
                new GlNativeEntry(
                        new GlNativeEntry.Key(action.postingId(), entry.entryKey()),
                        action.tenantId(),
                        action.bookId(),
                        action.actionId(),
                        entry.description());
        return repository
                .appendEntry(row)
                .switchIfEmpty(Mono.error(unavailable()))
                .thenMany(
                        Flux.fromIterable(entry.lines())
                                .concatMap(
                                        line ->
                                                repository
                                                        .appendLine(line(action, entry, line))
                                                        .switchIfEmpty(Mono.error(unavailable()))))
                .then();
    }

    private static GlNativeLine line(
            GlNativeJournalAction action, GlPostingEntry entry, GlPostingLine line) {
        return new GlNativeLine(
                new GlNativeLine.Key(action.postingId(), entry.entryKey(), line.lineKey()),
                action.tenantId(),
                action.bookId(),
                action.actionId(),
                uuid(line.accountId()),
                line.expectedAccountVersion(),
                uuid(line.currencyAdmissionId()),
                line.expectedCurrencyVersion(),
                line.postedScale(),
                line.currency().digest(),
                line.side(),
                line.amountMinor(),
                line.memo());
    }

    private static GlNativeJournalEvent event(
            UUID tenant, UUID book, UUID action, UUID receipt, String digest, String channel) {
        try {
            var value = new LinkedHashMap<String, String>();
            value.put("actionId", action.toString());
            value.put("receiptId", receipt.toString());
            value.put("intentDigest", digest);
            value.put("channel", channel);
            var payload = JsonMapper.builder().build().writeValueAsString(value);
            return new GlNativeJournalEvent(
                    new GlNativeJournalEvent.Key(action, channel),
                    tenant,
                    book,
                    "GL_ACCOUNTING_EVENT_V1",
                    payload,
                    GlPostingRules.digest(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw unavailable();
        }
    }

    private long currencyUntil(GlObservedCurrency value) {
        long now = Instant.now().toEpochMilli();
        long maxAge =
                environment.getProperty(
                        "firefly.accounting.native-posting.observation-max-age-millis",
                        Long.class,
                        5000L);
        if (maxAge < 1
                || maxAge > 60000
                || value.observedAtEpochMillis() > now
                || value.observedAtEpochMillis() < now - maxAge
                || value.publishedAtEpochMillis() > value.observedAtEpochMillis()
                || value.effectiveFromEpochMillis() > now
                || (value.effectiveToEpochMillis() != null
                        && value.effectiveToEpochMillis() <= now)) throw conflict();
        return Math.min(
                value.observedAtEpochMillis() + maxAge,
                value.effectiveToEpochMillis() == null
                        ? Long.MAX_VALUE
                        : value.effectiveToEpochMillis());
    }

    private static void source(
            GlAccountingAuthority.Admission a,
            GlPostingRules.Validated v,
            GlPostingSourceObservation s) {
        if (!a.intent().tenantId().equals(s.tenantId())
                || !a.intent().bookId().equals(s.bookId())
                || !v.request().actionId().equals(s.actionId())
                || !v.intentDigest().equals(s.intentDigest())
                || !v.sourceClaimKey().equals(s.sourceClaimKey())
                || !a.actor().issuer().equals(s.actorIssuer())
                || !a.actor().subject().equals(s.actorSubject())
                || s.validUntilEpochMillis() <= Instant.now().toEpochMilli()
                || s.validUntilEpochMillis() > GlPostingRules.MAX_TIME) throw denied();
    }

    private static SortedSet<String> accounts(GlPostingRules.Validated v) {
        var ids = new TreeSet<String>();
        v.request().entries().forEach(e -> e.lines().forEach(l -> ids.add(l.accountId())));
        return Collections.unmodifiableSortedSet(ids);
    }

    private void enabled() {
        if (!Boolean.TRUE.equals(
                environment.getProperty(
                        "firefly.accounting.native-posting.enabled", Boolean.class)))
            throw unavailable();
    }

    private static <T> T required(ObjectProvider<T> provider) {
        var value = provider.getIfAvailable();
        if (value == null) throw unavailable();
        return value;
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Native posting dependencies or retained graph unavailable");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "Exact current native posting context required");
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Current exact source admission required");
    }
}
