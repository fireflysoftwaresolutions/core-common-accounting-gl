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
import io.fireflysoftware.core.accounting.core.services.journal.JournalBatchService;
import io.fireflysoftware.core.accounting.intefaces.dtos.journal.JournalBatchDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/journal-batches")
@RequiredArgsConstructor
@Tag(name = "Journal Batches", description = "API for managing Journal Batches")
public class JournalBatchController {

    private final JournalBatchService journalBatchService;

    @Operation(summary = "Create a new Journal Batch")
    @ApiResponse(responseCode = "201", description = "Journal Batch created")
    @PostMapping
    public Mono<ResponseEntity<JournalBatchDTO>> create(@RequestBody JournalBatchDTO dto) {
        return journalBatchService.create(dto)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Get a Journal Batch by ID")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/{id}")
    public Mono<ResponseEntity<JournalBatchDTO>> getById(@PathVariable("id") UUID id) {
        return journalBatchService.getById(id)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Update an existing Journal Batch")
    @ApiResponse(responseCode = "200", description = "Batch updated")
    @PutMapping("/{id}")
    public Mono<ResponseEntity<JournalBatchDTO>> update(@PathVariable("id") UUID id,
                                                        @RequestBody JournalBatchDTO dto) {
        return journalBatchService.update(id, dto)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Delete a Journal Batch")
    @ApiResponse(responseCode = "204", description = "Batch deleted")
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("id") UUID id) {
        return journalBatchService.delete(id)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @Operation(summary = "Search/Filter Journal Batches")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/")
    public Mono<ResponseEntity<PaginationResponse<JournalBatchDTO>>> search(
            @ParameterObject @ModelAttribute FilterRequest<JournalBatchDTO> filterRequest) {
        return journalBatchService.search(filterRequest)
                .map(ResponseEntity::ok);
    }
}
