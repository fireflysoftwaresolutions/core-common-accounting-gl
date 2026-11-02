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


package io.fireflysoftware.core.accounting.web.controllers.journal;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.accounting.core.services.journal.JournalLineService;
import io.fireflysoftware.core.accounting.intefaces.dtos.journal.JournalLineDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/journal-batches/{batchId}/entries/{entryId}/lines")
@RequiredArgsConstructor
@Tag(name = "Journal Lines", description = "API for managing Journal Lines (nested under Journal Entries)")
public class JournalLineController {

    private final JournalLineService journalLineService;

    @Operation(summary = "Create a new Journal Line in a specific Entry")
    @ApiResponse(responseCode = "201", description = "Journal Line created")
    @PostMapping
    public Mono<ResponseEntity<JournalLineDTO>> create(@PathVariable("batchId") UUID batchId,
                                                       @PathVariable("entryId") UUID entryId,
                                                       @RequestBody JournalLineDTO dto) {
        return journalLineService.create(batchId, entryId, dto)
                .map(createdDto -> ResponseEntity.status(201).body(createdDto));
    }

    @Operation(summary = "Get a Journal Line by ID")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/{lineId}")
    public Mono<ResponseEntity<JournalLineDTO>> getById(@PathVariable("batchId") UUID batchId,
                                                        @PathVariable("entryId") UUID entryId,
                                                        @PathVariable("lineId") UUID lineId) {
        return journalLineService.getById(batchId, entryId, lineId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Update a Journal Line")
    @ApiResponse(responseCode = "200", description = "Line updated")
    @PutMapping("/{lineId}")
    public Mono<ResponseEntity<JournalLineDTO>> update(@PathVariable("batchId") UUID batchId,
                                                       @PathVariable("entryId") UUID entryId,
                                                       @PathVariable("lineId") UUID lineId,
                                                       @RequestBody JournalLineDTO dto) {
        return journalLineService.update(batchId, entryId, lineId, dto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete a Journal Line")
    @ApiResponse(responseCode = "204", description = "Line deleted")
    @DeleteMapping("/{lineId}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("batchId") UUID batchId,
                                             @PathVariable("entryId") UUID entryId,
                                             @PathVariable("lineId") UUID lineId) {
        return journalLineService.delete(batchId, entryId, lineId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @Operation(summary = "Search/Filter Journal Lines")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/")
    public Mono<ResponseEntity<PaginationResponse<JournalLineDTO>>> search(@PathVariable("batchId") UUID batchId,
                                                                           @PathVariable("entryId") UUID entryId,
                                                                           @RequestBody FilterRequest<JournalLineDTO> filterRequest) {
        return journalLineService.search(batchId, entryId, filterRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}