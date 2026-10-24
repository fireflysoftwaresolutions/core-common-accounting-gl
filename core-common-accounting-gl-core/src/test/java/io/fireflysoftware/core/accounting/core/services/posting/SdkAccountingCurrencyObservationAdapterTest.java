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
package io.fireflysoftware.core.accounting.core.services.posting;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.fireflysoftware.common.reference.master.data.sdk.api.CurrencyExecutionPoliciesApi;
import io.fireflysoftware.common.reference.master.data.sdk.invoker.ApiClient;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.GlCurrencyReference;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.reactive.TransactionContextManager;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

class SdkAccountingCurrencyObservationAdapterTest {
    static final String POLICY = "12345678-1234-4234-8234-123456789abc";
    static final String CURRENCY = "87654321-4321-4321-8321-cba987654321";
    static final ObjectMapper JSON = new ObjectMapper();
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicInteger requests = new AtomicInteger();
    HttpServer server;
    SdkAccountingCurrencyObservationAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/api/v1/currency-execution-policies/",
                exchange -> {
                    requests.incrementAndGet();
                    assertThat(exchange.getRequestURI().getPath()).endsWith(POLICY);
                    assertThat(exchange.getRequestURI().getRawQuery())
                            .isEqualTo("currencyId=" + CURRENCY);
                    byte[] data = body.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
        var client =
                new ApiClient().setBasePath("http://127.0.0.1:" + server.getAddress().getPort());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("configuredCurrencySdk", new CurrencyExecutionPoliciesApi(client));
        adapter =
                new SdkAccountingCurrencyObservationAdapter(
                        beans.getBeanProvider(CurrencyExecutionPoliciesApi.class),
                        new org.springframework.mock.env.MockEnvironment()
                                .withProperty(
                                        "firefly.accounting.currency-observation.allow-loopback-http",
                                        "true"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    record Wire(GlCurrencyReference reference, Map<String, Object> data) {}

    Wire wire(int scale) throws Exception {
        long now = System.currentTimeMillis();
        var source =
                "MASTERS_CURRENCY_SOURCE_V1\n{\"currencyId\":\""
                        + CURRENCY
                        + "\",\"isoCode\":\"USD\"}";
        var definition = new TreeMap<String, Object>();
        definition.put("currencyId", CURRENCY);
        definition.put("isoCode", "USD");
        definition.put("version", 1);
        definition.put("sourceEncoding", source);
        definition.put("sourceDigest", GlPostingRules.digest(source));
        definition.put("postedScale", scale);
        definition.put("effectiveFromEpochMillis", now - 60000);
        definition.put("effectiveToEpochMillis", null);
        definition.put("provenanceReference", "Synthetic approved representation Ω🙂");
        var canonical = "MASTERS_CURRENCY_POLICY_V1\n" + JSON.writeValueAsString(definition);
        var reference =
                new GlCurrencyReference(
                        POLICY,
                        CURRENCY,
                        "USD",
                        1L,
                        GlPostingRules.digest(canonical),
                        "MASTERS_CURRENCY_POLICY_V1");
        var data = new HashMap<String, Object>();
        data.put(
                "reference",
                Map.of(
                        "policyId",
                        POLICY,
                        "currencyId",
                        CURRENCY,
                        "isoCode",
                        "USD",
                        "version",
                        1,
                        "digest",
                        reference.digest(),
                        "encodingVersion",
                        reference.encodingVersion()));
        data.put("canonicalEncoding", canonical);
        data.put("sourceEncoding", source);
        data.put("sourceDigest", GlPostingRules.digest(source));
        data.put("postedScale", scale);
        data.put("effectiveFromEpochMillis", now - 60000);
        data.put("effectiveToEpochMillis", null);
        data.put("provenanceReference", definition.get("provenanceReference"));
        data.put("publicationActionId", "11111111-1111-4111-8111-111111111111");
        data.put("publishedAtEpochMillis", now - 60000);
        data.put("lifecycle", "PUBLISHED");
        data.put("stateVersion", 0);
        data.put("observedAtEpochMillis", now);
        data.put("effectiveNow", true);
        return new Wire(reference, data);
    }

    void send(Map<String, Object> data) throws Exception {
        body.set(JSON.writeValueAsString(data));
    }

    void denied(GlCurrencyReference ref, int status) {
        assertThatThrownBy(() -> adapter.observe(ref).block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void genuineGeneratedClientDecodesExactReferencesAndExplicitUnboundedEffectivity()
            throws Exception {
        for (int scale : List.of(0, 2, 3, 10)) {
            var wire = wire(scale);
            send(wire.data);
            var observed = adapter.observe(wire.reference).block(Duration.ofSeconds(5));
            assertThat(observed.reference()).isEqualTo(wire.reference);
            assertThat(observed.canonicalEncoding()).isEqualTo(wire.data.get("canonicalEncoding"));
            assertThat(observed.postedScale()).isEqualTo(scale);
            assertThat(observed.effectiveToEpochMillis()).isNull();
            assertThat(observed.provenanceReference())
                    .isEqualTo("Synthetic approved representation Ω🙂");
        }
        assertThat(requests).hasValue(4);
    }

    @Test
    void returnedReferenceDigestAndEncodedMeaningMustAllMatch() throws Exception {
        for (var changed :
                List.of(
                        "reference",
                        "canonicalEncoding",
                        "postedScale",
                        "sourceDigest",
                        "provenanceReference")) {
            var wire = wire(2);
            switch (changed) {
                case "reference" ->
                        wire.data.put(
                                "reference",
                                Map.of(
                                        "policyId",
                                        POLICY,
                                        "currencyId",
                                        UUID.randomUUID().toString(),
                                        "isoCode",
                                        "USD",
                                        "version",
                                        1,
                                        "digest",
                                        wire.reference.digest(),
                                        "encodingVersion",
                                        wire.reference.encodingVersion()));
                case "postedScale" -> wire.data.put(changed, 3);
                case "sourceDigest" -> wire.data.put(changed, "f".repeat(64));
                default -> wire.data.put(changed, "changed");
            }
            send(wire.data);
            denied(wire.reference, 502);
        }
    }

    @Test
    void retiredOrNotCurrentlyEffectivePolicyCannotBeInitiallyAdmitted() throws Exception {
        for (var field : List.of("lifecycle", "effectiveNow")) {
            var wire = wire(2);
            wire.data.put(field, field.equals("lifecycle") ? "RETIRED" : false);
            send(wire.data);
            denied(wire.reference, 409);
        }
    }

    @Test
    void missingNullableEndOrRequiredMetadataNeverBecomesAnAssumedDefault() throws Exception {
        for (var field :
                List.of(
                        "effectiveToEpochMillis",
                        "postedScale",
                        "stateVersion",
                        "observedAtEpochMillis",
                        "effectiveNow")) {
            var wire = wire(2);
            wire.data.remove(field);
            send(wire.data);
            denied(wire.reference, 502);
        }
    }

    @Test
    void unconfiguredSdkFailsClosedAndDoesNotPerformNetworkRequests() {
        var beans = new DefaultListableBeanFactory();
        var missing =
                new SdkAccountingCurrencyObservationAdapter(
                        beans.getBeanProvider(CurrencyExecutionPoliciesApi.class));
        assertThatThrownBy(
                        () ->
                                missing.observe(
                                                new GlCurrencyReference(
                                                        POLICY,
                                                        CURRENCY,
                                                        "USD",
                                                        1L,
                                                        "a".repeat(64),
                                                        "MASTERS_CURRENCY_POLICY_V1"))
                                        .block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        assertThat(requests).hasValue(0);
    }

    @Test
    void ambientReactiveTransactionIsRejectedBeforeAnySdkIo() throws Exception {
        var wire = wire(2);
        send(wire.data);
        var call =
                TransactionSynchronizationManager.forCurrentTransaction()
                        .flatMap(
                                manager -> {
                                    manager.setActualTransactionActive(true);
                                    return adapter.observe(wire.reference);
                                })
                        .contextWrite(TransactionContextManager.createTransactionContext());
        assertThatThrownBy(call::block)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(requests).hasValue(0);
    }

    @Test
    void insecureClientConfigurationNeedsExplicitLoopbackSandboxOptIn() throws Exception {
        var wire = wire(2);
        send(wire.data);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton(
                "insecureClient",
                new CurrencyExecutionPoliciesApi(
                        new ApiClient()
                                .setBasePath("http://127.0.0.1:" + server.getAddress().getPort())));
        adapter =
                new SdkAccountingCurrencyObservationAdapter(
                        beans.getBeanProvider(CurrencyExecutionPoliciesApi.class));
        denied(wire.reference, 503);
        assertThat(requests).hasValue(0);
    }
}
