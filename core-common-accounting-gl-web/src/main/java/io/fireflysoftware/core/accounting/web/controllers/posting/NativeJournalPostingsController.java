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

import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/books/{bookId}/postings")
@Tag(name = "NativeJournalPostings", description = "Protected exact posting and same-request recovery; no reversal or no-effect closure")
@SecurityScheme(name = "glBearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SecurityRequirement(name = "glBearer")
@ApiResponses({
        @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = GlPostingErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Current bearer authentication required"),
        @ApiResponse(responseCode = "403", description = "Current scoped policy and owner approval required"),
        @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = GlPostingErrorResponse.class))),
        @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = GlPostingErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Unavailable or uncertain: retain the original action and use protected recovery; never infer no effects",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = GlPostingErrorResponse.class))),
        @ApiResponse(responseCode = "default", description = "Other failures retain their HTTP status; no failure implies no effects or authorizes a new action")
})
public class NativeJournalPostingsController {
    private static final int MAX_BYTES = 1_048_576;
    private final GlPostingApplicationService postings;

    public NativeJournalPostingsController(GlPostingApplicationService postings) { this.postings = postings; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @DisableIdempotency
    @Operation(operationId = "postNativeJournal", summary = "Post the exact original journal request",
            description = "The action ID and complete original request remain stable across recovery. Current owner approval, source and currency observations are required. A non-success response does not prove no financial effects.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GlPostingRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = GlPostingReceipt.class)))
    public Mono<ResponseEntity<GlPostingReceipt>> post(@PathVariable String tenantId,
            @PathVariable String bookId, ServerHttpRequest request) {
        return invoke(tenantId, bookId, request, false);
    }

    @PostMapping(value = "/recovery", consumes = MediaType.APPLICATION_JSON_VALUE)
    @DisableIdempotency
    @Operation(operationId = "recoverNativeJournal", summary = "Read the retained posting using the complete original request",
            description = "Authorized recovery performs no new posting. A 404 is an observation at this read, not an audited no-effect closure or permission to allocate a new action.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GlPostingRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = GlPostingReceipt.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = GlPostingErrorResponse.class)))
    public Mono<ResponseEntity<GlPostingReceipt>> recover(@PathVariable String tenantId,
            @PathVariable String bookId, ServerHttpRequest request) {
        return invoke(tenantId, bookId, request, true);
    }

    private Mono<ResponseEntity<GlPostingReceipt>> invoke(String tenant, String book,
            ServerHttpRequest request, boolean recovery) {
        return original(request).flatMap(bytes -> {
            var expected = GlPostingIntentCodec.decode(tenant, book, bytes).validated();
            return Mono.defer(() -> recovery ? postings.recover(tenant, book, bytes) : postings.post(tenant, book, bytes))
                    .onErrorMap(error -> {
                        java.util.UUID action = null;
                        boolean observation = false;
                        if (error instanceof GlPostingCommitKnownException known) {
                            observation = true; action = known.actionReference();
                        } else if (error instanceof GlPostingOutcomeUncertainException uncertain) {
                            observation = true; action = uncertain.actionReference();
                        }
                        if (observation && (action == null || !action.toString().equals(expected.request().actionId())))
                            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                    "Unbound posting observation; original recovery required");
                        return error;
                    })
                    .switchIfEmpty(Mono.error(unobserved()))
                    .map(receipt -> {
                        if (receipt.receiptId() == null || receipt.actionId() == null || receipt.postingId() == null
                                || receipt.journalSequence() <= 0 || receipt.recordedAt() == null
                                || !receipt.actionId().toString().equals(expected.request().actionId())
                                || !expected.intentDigest().equals(receipt.intentDigest())
                                || recovery && !receipt.recovered()) throw unobserved();
                        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                                .cacheControl(CacheControl.noStore()).body(receipt);
                    });
        });
    }

    private Mono<byte[]> original(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody(), MAX_BYTES)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Complete original posting request required")))
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally { DataBufferUtils.release(buffer); }
                })
                .onErrorMap(DataBufferLimitException.class,
                        error -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bounded original posting request required"));
    }

    private static ResponseStatusException unobserved() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Exact protected posting receipt unobserved; original recovery required");
    }
}
