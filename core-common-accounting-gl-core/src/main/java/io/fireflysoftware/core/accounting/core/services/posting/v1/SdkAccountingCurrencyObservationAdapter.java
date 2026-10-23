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
package io.fireflysoftware.core.accounting.core.services.posting.v1;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.fireflysoftware.common.reference.master.data.sdk.api.CurrencyExecutionPoliciesApi;
import io.fireflysoftware.common.reference.master.data.sdk.model.CurrencyPolicyView;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.v1.GlCurrencyReference;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Component
public final class SdkAccountingCurrencyObservationAdapter
        implements AccountingCurrencyObservationPort {
    private static final String POLICY_ENCODING = "MASTERS_CURRENCY_POLICY_V1";
    private static final String SOURCE_ENCODING = "MASTERS_CURRENCY_SOURCE_V1";
    private static final Set<String> DEFINITION_FIELDS =
            Set.of(
                    "currencyId",
                    "isoCode",
                    "version",
                    "sourceEncoding",
                    "sourceDigest",
                    "postedScale",
                    "effectiveFromEpochMillis",
                    "effectiveToEpochMillis",
                    "provenanceReference");
    private static final JsonMapper JSON =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    static {
        JSON.getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder()
                                .maxStringLength(1048576)
                                .maxNestingDepth(20)
                                .maxNumberLength(30)
                                .build());
    }

    private final ObjectProvider<CurrencyExecutionPoliciesApi> clients;
    private final Environment environment;

    public SdkAccountingCurrencyObservationAdapter(
            ObjectProvider<CurrencyExecutionPoliciesApi> clients) {
        this(clients, new StandardEnvironment());
    }

    @Autowired
    public SdkAccountingCurrencyObservationAdapter(
            ObjectProvider<CurrencyExecutionPoliciesApi> clients, Environment environment) {
        this.clients = clients;
        this.environment = environment;
    }

    @Override
    public Mono<GlObservedCurrency> observe(GlCurrencyReference reference) {
        return GlAccountingBoundary.outsideTransaction(
                () ->
                        Mono.defer(
                                () -> {
                                    GlPostingRules.currency(reference);
                                    CurrencyExecutionPoliciesApi client;
                                    try {
                                        client = clients.getIfAvailable();
                                    } catch (RuntimeException error) {
                                        return Mono.error(unconfigured());
                                    }
                                    if (client == null)
                                        return Mono.error(
                                                new ResponseStatusException(
                                                        HttpStatus.SERVICE_UNAVAILABLE,
                                                        "Authenticated currency policy client is"
                                                            + " not configured"));
                                    requireEndpoint(client);
                                    return client.getCurrencyExecutionPolicy(
                                                    reference.policyId(), reference.currencyId())
                                            .map(value -> checked(reference, value))
                                            .switchIfEmpty(Mono.error(invalid()))
                                            .timeout(Duration.ofSeconds(4))
                                            .onErrorMap(
                                                    error ->
                                                            error instanceof ResponseStatusException
                                                                    ? error
                                                                    : invalid());
                                }));
    }

    private void requireEndpoint(CurrencyExecutionPoliciesApi client) {
        try {
            var uri = URI.create(client.getApiClient().getBasePath());
            if (uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) throw unconfigured();
            if ("https".equals(uri.getScheme())) return;
            if ("http".equals(uri.getScheme())
                    && Set.of("127.0.0.1", "::1", "[::1]").contains(uri.getHost())
                    && Boolean.TRUE.equals(
                            environment.getProperty(
                                    "firefly.accounting.currency-observation.allow-loopback-http",
                                    Boolean.class))) return;
            throw unconfigured();
        } catch (RuntimeException error) {
            throw unconfigured();
        }
    }

    private static ResponseStatusException unconfigured() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Authenticated secure currency policy client required");
    }

    private static GlObservedCurrency checked(
            GlCurrencyReference requested, CurrencyPolicyView value) {
        try {
            if (value == null || value.getReference() == null) throw invalid();
            var r = value.getReference();
            var actual =
                    new GlCurrencyReference(
                            r.getPolicyId(),
                            r.getCurrencyId(),
                            r.getIsoCode(),
                            r.getVersion(),
                            r.getDigest(),
                            r.getEncodingVersion());
            if (!requested.equals(actual)) throw invalid();
            GlPostingRules.currency(actual);
            GlPostingRules.text(value.getCanonicalEncoding(), 1048576);
            GlPostingRules.text(value.getSourceEncoding(), 1048576);
            GlPostingRules.digestField(value.getSourceDigest());
            GlPostingRules.text(value.getProvenanceReference(), 500);
            GlPostingRules.uuid(value.getPublicationActionId());
            GlPostingRules.time(value.getPublishedAtEpochMillis());
            GlPostingRules.time(value.getObservedAtEpochMillis());
            GlPostingRules.time(value.getEffectiveFromEpochMillis());
            if (value.getEffectiveToEpochMillis_JsonNullable() == null
                    || !value.getEffectiveToEpochMillis_JsonNullable().isPresent()
                    || value.getPostedScale() == null
                    || value.getPostedScale() < 0
                    || value.getPostedScale() > 10
                    || value.getStateVersion() == null
                    || value.getEffectiveNow() == null
                    || !Set.of("PUBLISHED", "RETIRED").contains(value.getLifecycle()))
                throw invalid();
            var end = value.getEffectiveToEpochMillis();
            if (end != null) {
                GlPostingRules.time(end);
                if (end <= value.getEffectiveFromEpochMillis()) throw invalid();
            }
            if (!actual.digest().equals(GlPostingRules.digest(value.getCanonicalEncoding()))
                    || !value.getSourceDigest()
                            .equals(GlPostingRules.digest(value.getSourceEncoding())))
                throw invalid();
            var definition = document(value.getCanonicalEncoding(), POLICY_ENCODING);
            if (definition.size() != DEFINITION_FIELDS.size()) throw invalid();
            for (var key : DEFINITION_FIELDS) if (!definition.has(key)) throw invalid();
            exactText(definition, "currencyId", actual.currencyId());
            exactText(definition, "isoCode", actual.isoCode());
            exactNumber(definition, "version", actual.version());
            exactText(definition, "sourceEncoding", value.getSourceEncoding());
            exactText(definition, "sourceDigest", value.getSourceDigest());
            exactNumber(definition, "postedScale", value.getPostedScale().longValue());
            exactNumber(
                    definition, "effectiveFromEpochMillis", value.getEffectiveFromEpochMillis());
            if (end == null) {
                if (!definition.get("effectiveToEpochMillis").isNull()) throw invalid();
            } else exactNumber(definition, "effectiveToEpochMillis", end);
            exactText(definition, "provenanceReference", value.getProvenanceReference());
            var source = document(value.getSourceEncoding(), SOURCE_ENCODING);
            exactText(source, "currencyId", actual.currencyId());
            exactText(source, "isoCode", actual.isoCode());
            if (value.getPublishedAtEpochMillis() > value.getObservedAtEpochMillis())
                throw invalid();
            if ("RETIRED".equals(value.getLifecycle()) || !value.getEffectiveNow())
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Exact currency policy is not currently eligible for initial admission");
            if (value.getStateVersion() != 0L) throw invalid();
            long now = Instant.now().toEpochMilli();
            if (value.getEffectiveFromEpochMillis() > value.getObservedAtEpochMillis()
                    || (end != null && value.getObservedAtEpochMillis() >= end)
                    || value.getEffectiveFromEpochMillis() > now
                    || (end != null && now >= end)) throw invalid();
            return new GlObservedCurrency(
                    actual,
                    value.getCanonicalEncoding(),
                    value.getSourceEncoding(),
                    value.getSourceDigest(),
                    value.getPostedScale(),
                    value.getEffectiveFromEpochMillis(),
                    end,
                    value.getProvenanceReference(),
                    value.getPublicationActionId(),
                    value.getPublishedAtEpochMillis(),
                    value.getObservedAtEpochMillis());
        } catch (ResponseStatusException error) {
            if (error.getStatusCode().value() == 409 || error.getStatusCode().value() == 502)
                throw error;
            throw invalid();
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    private static JsonNode document(String value, String encoding) {
        try {
            var prefix = encoding + "\n";
            if (!value.startsWith(prefix)) throw invalid();
            var parser = JSON.getFactory().createParser(value.substring(prefix.length()));
            try (parser) {
                JsonNode document = JSON.readTree(parser);
                if (document == null || !document.isObject() || parser.nextToken() != null)
                    throw invalid();
                return document;
            }
        } catch (java.io.IOException error) {
            throw invalid();
        }
    }

    private static void exactText(JsonNode object, String field, String expected) {
        var value = object.get(field);
        if (value == null || !value.isTextual() || !expected.equals(value.textValue()))
            throw invalid();
    }

    private static void exactNumber(JsonNode object, String field, Long expected) {
        var value = object.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() != expected) throw invalid();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "Exact coherent currency policy observation required");
    }
}
