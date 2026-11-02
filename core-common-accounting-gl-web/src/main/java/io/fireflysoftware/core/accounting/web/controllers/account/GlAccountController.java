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


package io.fireflysoftware.core.accounting.web.controllers.account;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.accounting.core.services.account.GlAccountService;
import io.fireflysoftware.core.accounting.intefaces.dtos.account.GlAccountDTO;
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
@RequestMapping("/api/v1/gl-accounts")
@RequiredArgsConstructor
@Tag(name = "GL Accounts", description = "API for managing GL Accounts")
public class GlAccountController {

    private final GlAccountService glAccountService;

    @Operation(summary = "Create GL Account")
    @ApiResponse(responseCode = "201", description = "GL Account created")
    @PostMapping
    public Mono<ResponseEntity<GlAccountDTO>> create(@RequestBody GlAccountDTO dto) {
        return glAccountService.create(dto)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Get GL Account by ID")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/{id}")
    public Mono<ResponseEntity<GlAccountDTO>> getById(@PathVariable("id") UUID id) {
        return glAccountService.getById(id)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Update GL Account")
    @ApiResponse(responseCode = "200", description = "GL Account updated")
    @PutMapping("/{id}")
    public Mono<ResponseEntity<GlAccountDTO>> update(@PathVariable("id") UUID id,
                                     @RequestBody GlAccountDTO dto) {
        return glAccountService.update(id, dto)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Delete GL Account")
    @ApiResponse(responseCode = "204", description = "GL Account deleted")
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("id") UUID id) {
        return glAccountService.delete(id)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @Operation(summary = "Search/Filter GL Accounts")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/")
    public Mono<ResponseEntity<PaginationResponse<GlAccountDTO>>> search(
            @ParameterObject @ModelAttribute FilterRequest<GlAccountDTO> filterRequest) {
        return glAccountService.search(filterRequest)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "List child GL Accounts for a given parent")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/{parentId}/children")
    public Mono<ResponseEntity<PaginationResponse<GlAccountDTO>>> findChildren(
            @PathVariable("parentId") UUID parentId,
            @ParameterObject @ModelAttribute FilterRequest<GlAccountDTO> filterRequest) {
        return glAccountService.findChildren(parentId, filterRequest)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Create a child GL Account under a parent")
    @ApiResponse(responseCode = "201", description = "Child GL Account created")
    @PostMapping("/{parentId}/children")
    public Mono<ResponseEntity<GlAccountDTO>> createChild(@PathVariable("parentId") UUID parentId,
                                          @RequestBody GlAccountDTO dto) {
        return glAccountService.createChild(parentId, dto)
                .map(ResponseEntity::ok);
    }
}