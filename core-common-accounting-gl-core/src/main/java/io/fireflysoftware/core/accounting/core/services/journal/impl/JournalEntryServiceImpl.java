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

package io.fireflysoftware.core.accounting.core.services.journal.impl;

import io.fireflysoftware.core.accounting.core.services.journal.JournalEntryService;
import io.fireflysoftware.core.accounting.core.services.journal.LegacyJournalGuard;

import io.fireflysoftware.core.accounting.core.mappers.journal.JournalEntryMapper;
import io.fireflysoftware.core.accounting.intefaces.dtos.journal.JournalEntryDTO;
import io.fireflysoftware.core.accounting.models.entities.journal.JournalEntry;
import io.fireflysoftware.core.accounting.models.repositories.journal.JournalEntryRepository;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@Transactional
public class JournalEntryServiceImpl implements JournalEntryService {

    @Autowired private JournalEntryRepository repository;

    @Autowired private JournalEntryMapper mapper;

    @Autowired private LegacyJournalGuard guard;

    @Override
    public Mono<JournalEntryDTO> create(UUID batchId, JournalEntryDTO dto) {
        return Mono.defer(
                        () -> {
                            if (dto.isPosted()) return Mono.error(LegacyJournalGuard.conflict());
                            var entity = mapper.toEntity(dto);
                            entity.setId(null);
                            entity.setBatchId(batchId);
                            entity.setCreatedAt(java.time.LocalDateTime.now());
                            entity.setUpdatedAt(entity.getCreatedAt());
                            return guard.batch(batchId, true)
                                    .then(repository.save(entity))
                                    .map(mapper::toDTO);
                        })
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<JournalEntryDTO> getById(UUID batchId, UUID entryId) {
        return repository
                .findById(entryId)
                .filter(entity -> entity.getBatchId().equals(batchId))
                .map(mapper::toDTO)
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()));
    }

    @Override
    public Mono<JournalEntryDTO> update(UUID batchId, UUID entryId, JournalEntryDTO dto) {
        return guard.entry(batchId, entryId, true)
                .then(
                        Mono.defer(
                                () -> {
                                    if (dto.isPosted())
                                        return Mono.error(LegacyJournalGuard.conflict());
                                    return repository
                                            .findById(entryId)
                                            .filter(entity -> entity.getBatchId().equals(batchId))
                                            .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()))
                                            .flatMap(
                                                    existingEntity -> {
                                                        existingEntity.setTransactionId(
                                                                dto.getTransactionId());
                                                        existingEntity.setSourceModule(
                                                                dto.getSourceModule());
                                                        existingEntity.setTotalAmount(
                                                                dto.getTotalAmount());
                                                        existingEntity.setDescription(
                                                                dto.getDescription());
                                                        existingEntity.setEntryDate(
                                                                dto.getEntryDate());
                                                        existingEntity.setUpdatedAt(
                                                                java.time.LocalDateTime.now());
                                                        return repository.save(existingEntity);
                                                    })
                                            .map(mapper::toDTO);
                                }))
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<Void> delete(UUID batchId, UUID entryId) {
        return guard.entry(batchId, entryId, true)
                .then(repository.findById(entryId))
                .filter(entity -> entity.getBatchId().equals(batchId))
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()))
                .flatMap(repository::delete)
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<PaginationResponse<JournalEntryDTO>> search(
            UUID batchId, FilterRequest<JournalEntryDTO> filterRequest) {
        return guard.batch(batchId, false)
                .then(
                        Mono.defer(
                                () -> {
                                    if (filterRequest.getFilters() == null)
                                        filterRequest.setFilters(new JournalEntryDTO());
                                    filterRequest.getFilters().setBatchId(batchId);
                                    return FilterUtils.createFilter(
                                                    JournalEntry.class, mapper::toDTO)
                                            .filter(filterRequest);
                                }));
    }
}
