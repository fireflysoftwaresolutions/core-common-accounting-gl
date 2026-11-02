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

package io.fireflysoftware.core.accounting.web.controllers.posting;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.*;
import io.fireflysoftware.core.accounting.web.controllers.posting.impl.NativeJournalPostingExceptionHandlerImpl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** In-process HTTP contract only; the owner port here does not prove financial admission. */
class NativeJournalPostingsControllerTest {
    static final String TENANT = "00000000-0000-0000-0000-000000000001";
    static final String BOOK = "00000000-0000-0000-0000-000000000002";
    static final String ROOT = "/api/v1/tenants/" + TENANT + "/books/" + BOOK + "/postings";
    byte[] body;
    GlPostingReceipt receipt;
    OwnerStubImpl owner;
    WebTestClient client;

    @BeforeEach void setUp() throws Exception {
        String action = "00000000-0000-0000-0000-000000000003";
        var currency = new GlCurrencyReference(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "USD", 1L, "a".repeat(64), "MASTERS_CURRENCY_POLICY_V1");
        String admission = UUID.randomUUID().toString();
        var debit = new GlPostingLine("001", UUID.randomUUID().toString(), 0L, admission, 0L,
                currency, 2, "DR", "100", "Synthetic debit");
        var credit = new GlPostingLine("002", UUID.randomUUID().toString(), 0L, admission, 0L,
                currency, 2, "CR", "100", "Synthetic credit");
        long now = Instant.now().toEpochMilli();
        var request = new GlPostingRequest(action, UUID.randomUUID().toString(), 0L, 0L,
                now + 60_000, Instant.ofEpochMilli(now).atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString(), now,
                new GlSourceReference("MANUAL_ADJUSTMENT", "synthetic-object", "synthetic-effect",
                        1L, "b".repeat(64), "SYNTHETIC_ADJUSTMENT", 0, "synthetic-receipt",
                        "synthetic-finite-approval", now),
                UUID.randomUUID().toString(), 1L, "c".repeat(64), "Synthetic HTTP transport only",
                List.of(new GlPostingEntry("001", "Synthetic entry", List.of(debit, credit))));
        body = JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsBytes(request);
        var intent = GlPostingIntentCodec.decode(TENANT, BOOK, body).validated();
        receipt = new GlPostingReceipt(UUID.randomUUID(), UUID.fromString(action), UUID.randomUUID(),
                1, intent.intentDigest(), false, Instant.now());
        owner = new OwnerStubImpl(); owner.result = bytes -> Mono.just(receipt);
        client = WebTestClient.bindToController(new NativeJournalPostingsController(owner))
                .controllerAdvice(new NativeJournalPostingExceptionHandlerImpl()).build();
    }

    WebTestClient.ResponseSpec send(String suffix, byte[] input) {
        return client.post().uri(ROOT + suffix).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input).exchange();
    }

    @Test void postPreservesCompleteOriginalBytesAndTypedReceipt() {
        send("", body).expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.actionId").isEqualTo(receipt.actionId().toString())
                .jsonPath("$.intentDigest").isEqualTo(receipt.intentDigest())
                .jsonPath("$.receiptId").isEqualTo(receipt.receiptId().toString());
        assertArrayEquals(body, owner.received); assertEquals(1, owner.posts.get());
        assertEquals(0, owner.reads.get());
    }

    @Test void recoveryUsesTheSameCompleteRequestAndNeverCallsPost() {
        receipt = new GlPostingReceipt(receipt.receiptId(), receipt.actionId(), receipt.postingId(),
                receipt.journalSequence(), receipt.intentDigest(), true, receipt.recordedAt());
        send("/recovery", body).expectStatus().isOk().expectBody().jsonPath("$.recovered").isEqualTo(true);
        assertArrayEquals(body, owner.received); assertEquals(0, owner.posts.get());
        assertEquals(1, owner.reads.get());
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 409, 429, 500, 502, 503, 504})
    void preservesEveryOwnerHttpStatusWithoutExposingItsMessage(int status) {
        owner.result = bytes -> Mono.error(new ResponseStatusException(HttpStatusCode.valueOf(status),
                "SYNTHETIC_PRIVATE_DIAGNOSTIC"));
        send("", body).expectStatus().isEqualTo(status).expectBody()
                .jsonPath("$.status").isEqualTo(status).jsonPath("$.code").isEqualTo("GL_POSTING_REQUEST_FAILED")
                .consumeWith(response -> assertFalse(new String(response.getResponseBody(), StandardCharsets.UTF_8)
                        .contains("SYNTHETIC_PRIVATE_DIAGNOSTIC")));
        assertEquals(1, owner.posts.get());
    }

    @ParameterizedTest @ValueSource(ints = {200, 302})
    void anExceptionCannotMasqueradeAsSuccessfulOrRedirectedPosting(int status) {
        owner.result = bytes -> Mono.error(new ResponseStatusException(HttpStatusCode.valueOf(status), "invalid error status"));
        send("", body).expectStatus().isEqualTo(500).expectBody()
                .jsonPath("$.code").isEqualTo("GL_POSTING_REQUEST_FAILED");
    }

    @Test void knownCommitDoesNotExposeAReceiptOrAuthorizeANewAction() {
        owner.result = bytes -> Mono.error(new GlPostingCommitKnownException(receipt.actionId()));
        send("", body).expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.code").isEqualTo("GL_POSTING_COMMIT_KNOWN_RECOVERY_REQUIRED")
                .jsonPath("$.actionReference").isEqualTo(receipt.actionId().toString())
                .jsonPath("$.receiptId").doesNotExist();
        assertEquals(1, owner.posts.get());
    }

    @Test void uncertainCommitRequiresOriginalRecoveryAndNeverBecomesAbsence() {
        owner.result = bytes -> Mono.error(new GlPostingOutcomeUncertainException(receipt.actionId()));
        send("", body).expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.code").isEqualTo("GL_POSTING_OUTCOME_UNCERTAIN_RECOVERY_REQUIRED")
                .jsonPath("$.actionReference").isEqualTo(receipt.actionId().toString());
        assertEquals(1, owner.posts.get()); assertEquals(0, owner.reads.get());
    }

    @Test void anUnboundCommitObservationDoesNotExposeAnotherAction() {
        owner.result = bytes -> Mono.error(new GlPostingCommitKnownException(UUID.randomUUID()));
        send("", body).expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.code").isEqualTo("GL_POSTING_REQUEST_FAILED")
                .jsonPath("$.actionReference").isEmpty();
    }

    @Test void emptyOrUnboundOwnerResultCannotBecomeHttpSuccess() {
        owner.result = bytes -> Mono.empty();
        send("", body).expectStatus().isEqualTo(502);
        owner.result = bytes -> Mono.just(new GlPostingReceipt(receipt.receiptId(), UUID.randomUUID(),
                receipt.postingId(), 1, receipt.intentDigest(), false, receipt.recordedAt()));
        send("", body).expectStatus().isEqualTo(502);
        owner.result = bytes -> Mono.just(new GlPostingReceipt(receipt.receiptId(), receipt.actionId(),
                receipt.postingId(), 1, "d".repeat(64), false, receipt.recordedAt()));
        send("", body).expectStatus().isEqualTo(502);
    }

    @Test void recoveryCannotReturnANewMutationReceipt() {
        send("/recovery", body).expectStatus().isEqualTo(502);
        assertEquals(0, owner.posts.get()); assertEquals(1, owner.reads.get());
    }

    @Test void malformedDuplicateUnknownAndInvalidUtf8InputsNeverReachOwner() {
        String valid = new String(body, StandardCharsets.UTF_8);
        for (byte[] input : List.of("{}".getBytes(StandardCharsets.UTF_8),
                valid.replaceFirst("\\{", "{\"unknown\":true,").getBytes(StandardCharsets.UTF_8),
                valid.replaceFirst("\\{", "{\"actionId\":\"duplicate\",").getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xc3, 0x28})) {
            send("", input).expectStatus().isBadRequest();
        }
        assertEquals(0, owner.posts.get()); assertEquals(0, owner.reads.get());
    }

    @Test void emptyAndOversizedBodiesNeverReachOwner() {
        send("", new byte[0]).expectStatus().isBadRequest();
        send("", new byte[1_048_577]).expectStatus().isBadRequest();
        assertEquals(0, owner.posts.get());
    }

    @Test void unknownFailureHasNoRawThrowableOrFalseRefusalClaim() {
        owner.result = bytes -> Mono.error(new IllegalStateException("SYNTHETIC_SECRET"));
        send("", body).expectStatus().isEqualTo(500).expectBody()
                .jsonPath("$.code").isEqualTo("GL_POSTING_REQUEST_FAILED")
                .consumeWith(response -> assertFalse(new String(response.getResponseBody(), StandardCharsets.UTF_8)
                        .contains("SYNTHETIC_SECRET")));
    }

    @Test void declarationsDescribeTypedJsonRatherThanAStringOrBase64Request() throws Exception {
        for (String name : List.of("post", "recover")) {
            var method = NativeJournalPostingsController.class.getMethod(name, String.class, String.class,
                    org.springframework.http.server.reactive.ServerHttpRequest.class);
            assertNotNull(method.getAnnotation(org.fireflyframework.web.idempotency.annotation.DisableIdempotency.class));
            var input = method.getAnnotation(io.swagger.v3.oas.annotations.parameters.RequestBody.class);
            assertTrue(input.required()); assertEquals("application/json", input.content()[0].mediaType());
            assertEquals(GlPostingRequest.class, input.content()[0].schema().implementation());
            var responses = method.getAnnotationsByType(io.swagger.v3.oas.annotations.responses.ApiResponse.class);
            var success = java.util.Arrays.stream(responses).filter(value -> value.responseCode().equals("200")).findFirst().orElseThrow();
            assertEquals("application/json", success.content()[0].mediaType());
            assertEquals(GlPostingReceipt.class, success.content()[0].schema().implementation());
        }
        assertEquals("glBearer", NativeJournalPostingsController.class
                .getAnnotation(io.swagger.v3.oas.annotations.security.SecurityRequirement.class).name());
    }

    static final class OwnerStubImpl implements GlPostingApplicationService {
        final AtomicInteger posts = new AtomicInteger(), reads = new AtomicInteger();
        Function<byte[], Mono<GlPostingReceipt>> result;
        byte[] received;
        @Override public Mono<GlPostingReceipt> post(String tenant, String book, byte[] input) {
            assertEquals(TENANT, tenant); assertEquals(BOOK, book);
            posts.incrementAndGet(); received = input.clone(); return result.apply(input);
        }
        @Override public Mono<GlPostingReceipt> recover(String tenant, String book, byte[] input) {
            assertEquals(TENANT, tenant); assertEquals(BOOK, book);
            reads.incrementAndGet(); received = input.clone(); return result.apply(input);
        }
    }
}
