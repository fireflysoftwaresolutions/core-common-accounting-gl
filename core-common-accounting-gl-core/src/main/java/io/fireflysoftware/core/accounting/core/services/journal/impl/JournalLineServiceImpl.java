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

import io.fireflysoftware.core.accounting.core.services.journal.JournalLineService;
import io.fireflysoftware.core.accounting.core.services.journal.LegacyJournalGuard;

import io.fireflysoftware.core.accounting.core.mappers.journal.JournalLineMapper;
import io.fireflysoftware.core.accounting.intefaces.dtos.journal.JournalLineDTO;
import io.fireflysoftware.core.accounting.models.entities.journal.JournalLine;
import io.fireflysoftware.core.accounting.models.repositories.journal.JournalLineRepository;

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
public class JournalLineServiceImpl implements JournalLineService {

    @Autowired private JournalLineRepository repository;

    @Autowired private JournalLineMapper mapper;

    @Autowired private LegacyJournalGuard guard;

    @Override
    public Mono<JournalLineDTO> create(UUID batchId, UUID entryId, JournalLineDTO dto) {
        return guard.entry(batchId, entryId, true)
                .then(
                        Mono.defer(
                                () -> {
                                    var entity = mapper.toEntity(dto);
                                    entity.setId(null);
                                    entity.setEntryId(entryId);
                                    entity.setCreatedAt(java.time.LocalDateTime.now());
                                    entity.setUpdatedAt(entity.getCreatedAt());
                                    return repository.save(entity).map(mapper::toDTO);
                                }))
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<JournalLineDTO> getById(UUID batchId, UUID entryId, UUID lineId) {
        return guard.entry(batchId, entryId, false)
                .then(repository.findById(lineId))
                .filter(entity -> entity.getEntryId().equals(entryId))
                .map(mapper::toDTO)
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()));
    }

    @Override
    public Mono<JournalLineDTO> update(
            UUID batchId, UUID entryId, UUID lineId, JournalLineDTO dto) {
        return guard.entry(batchId, entryId, true)
                .then(repository.findById(lineId))
                .filter(entity -> entity.getEntryId().equals(entryId))
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()))
                .flatMap(
                        existingEntity -> {
                            existingEntity.setAccountId(dto.getAccountId());
                            existingEntity.setAmount(dto.getAmount());
                            existingEntity.setDrCr(dto.getDrCr());
                            existingEntity.setMemo(dto.getMemo());
                            existingEntity.setFxCurrency(dto.getFxCurrency());
                            existingEntity.setFxRate(dto.getFxRate());
                            existingEntity.setUpdatedAt(java.time.LocalDateTime.now());
                            return repository.save(existingEntity);
                        })
                .map(mapper::toDTO)
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<Void> delete(UUID batchId, UUID entryId, UUID lineId) {
        return guard.entry(batchId, entryId, true)
                .then(repository.findById(lineId))
                .filter(entity -> entity.getEntryId().equals(entryId))
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()))
                .flatMap(repository::delete)
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<PaginationResponse<JournalLineDTO>> search(
            UUID batchId, UUID entryId, FilterRequest<JournalLineDTO> filterRequest) {
        return guard.entry(batchId, entryId, false)
                .then(
                        Mono.defer(
                                () -> {
                                    if (filterRequest.getFilters() == null)
                                        filterRequest.setFilters(new JournalLineDTO());
                                    filterRequest.getFilters().setEntryId(entryId);
                                    return FilterUtils.createFilter(
                                                    JournalLine.class, mapper::toDTO)
                                            .filter(filterRequest);
                                }));
    }
}
