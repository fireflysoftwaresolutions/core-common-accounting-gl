/*
 * Copyright 2025 Firefly Software Foundation
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

package io.fireflysoftware.core.accounting.web.controllers.journal.v1;

import io.fireflysoftware.core.accounting.core.services.journal.v1.JournalEntryService;
import io.fireflysoftware.core.accounting.intefaces.dtos.journal.v1.JournalEntryDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/journal-batches/{batchId}/entries")
@RequiredArgsConstructor
@Tag(
        name = "Journal Entries",
        description = "API for managing Journal Entries (nested under Journal Batches)")
public class JournalEntryController {

    private final JournalEntryService journalEntryService;

    @ModelAttribute("filterRequest")
    public FilterRequest<JournalEntryDTO> journalEntryFilterRequest() {
        var request = new FilterRequest<JournalEntryDTO>();
        request.setFilters(new JournalEntryDTO());
        return request;
    }

    @Operation(summary = "Create a new Journal Entry in a specific Batch")
    @ApiResponse(responseCode = "201", description = "Journal Entry created")
    @PostMapping
    public Mono<ResponseEntity<JournalEntryDTO>> create(
            @PathVariable("batchId") UUID batchId, @RequestBody JournalEntryDTO dto) {
        return journalEntryService
                .create(batchId, dto)
                .map(entry -> ResponseEntity.status(201).body(entry));
    }

    @Operation(summary = "Get a Journal Entry by ID within a Batch")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/{entryId}")
    public Mono<ResponseEntity<JournalEntryDTO>> getById(
            @PathVariable("batchId") UUID batchId, @PathVariable("entryId") UUID entryId) {
        return journalEntryService
                .getById(batchId, entryId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Update a Journal Entry within a Batch")
    @ApiResponse(responseCode = "200", description = "Entry updated")
    @PutMapping("/{entryId}")
    public Mono<ResponseEntity<JournalEntryDTO>> update(
            @PathVariable("batchId") UUID batchId,
            @PathVariable("entryId") UUID entryId,
            @RequestBody JournalEntryDTO dto) {
        return journalEntryService.update(batchId, entryId, dto).map(ResponseEntity::ok);
    }

    @Operation(summary = "Delete a Journal Entry within a Batch")
    @ApiResponse(responseCode = "204", description = "Entry deleted")
    @DeleteMapping("/{entryId}")
    public Mono<ResponseEntity<Void>> delete(
            @PathVariable("batchId") UUID batchId, @PathVariable("entryId") UUID entryId) {
        return journalEntryService
                .delete(batchId, entryId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @Operation(summary = "Search/Filter Journal Entries within a Batch")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/")
    public Mono<ResponseEntity<PaginationResponse<JournalEntryDTO>>> search(
            @PathVariable("batchId") UUID batchId,
            @ParameterObject @ModelAttribute("filterRequest")
                    FilterRequest<JournalEntryDTO> filterRequest) {
        return journalEntryService.search(batchId, filterRequest).map(ResponseEntity::ok);
    }
}
