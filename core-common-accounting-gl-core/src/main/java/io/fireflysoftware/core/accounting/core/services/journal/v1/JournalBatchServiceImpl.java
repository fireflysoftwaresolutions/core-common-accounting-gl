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

package io.fireflysoftware.core.accounting.core.services.journal.v1;

import io.fireflysoftware.core.accounting.core.mappers.journal.v1.JournalBatchMapper;
import io.fireflysoftware.core.accounting.intefaces.dtos.journal.v1.JournalBatchDTO;
import io.fireflysoftware.core.accounting.models.entities.journal.v1.JournalBatch;
import io.fireflysoftware.core.accounting.models.repositories.journal.v1.JournalBatchRepository;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Transactional
public class JournalBatchServiceImpl implements JournalBatchService {

    @Autowired private JournalBatchRepository repository;

    @Autowired private JournalBatchMapper mapper;

    @Autowired private LegacyJournalGuard guard;

    @Override
    public Mono<JournalBatchDTO> create(JournalBatchDTO dto) {
        return Mono.defer(
                        () -> {
                            if (dto.getStatus()
                                    != io.fireflysoftware.core.accounting.intefaces.enums.journal.v1
                                            .JournalBatchStatusEnum.OPEN) {
                                return Mono.error(LegacyJournalGuard.conflict());
                            }
                            var entity = mapper.toEntity(dto);
                            entity.setId(null);
                            entity.setCreatedAt(LocalDateTime.now());
                            entity.setUpdatedAt(entity.getCreatedAt());
                            return repository.save(entity).map(mapper::toDTO);
                        })
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<JournalBatchDTO> getById(UUID id) {
        return repository
                .findById(id)
                .map(mapper::toDTO)
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()));
    }

    @Override
    public Mono<JournalBatchDTO> update(UUID id, JournalBatchDTO dto) {
        return Mono.defer(
                        () -> {
                            if (dto.getStatus()
                                    != io.fireflysoftware.core.accounting.intefaces.enums.journal.v1
                                            .JournalBatchStatusEnum.OPEN) {
                                return Mono.error(LegacyJournalGuard.conflict());
                            }
                            return guard.batch(id, true)
                                    .then(repository.findById(id))
                                    .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()))
                                    .flatMap(
                                            existingEntity -> {
                                                existingEntity.setReference(dto.getReference());
                                                existingEntity.setBatchDate(dto.getBatchDate());
                                                existingEntity.setRemarks(dto.getRemarks());
                                                existingEntity.setUpdatedAt(LocalDateTime.now());
                                                return repository.save(existingEntity);
                                            })
                                    .map(mapper::toDTO);
                        })
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<Void> delete(UUID id) {
        return guard.batch(id, true)
                .then(repository.findById(id))
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()))
                .flatMap(repository::delete)
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<PaginationResponse<JournalBatchDTO>> search(
            FilterRequest<JournalBatchDTO> filterRequest) {
        return FilterUtils.createFilter(JournalBatch.class, mapper::toDTO).filter(filterRequest);
    }
}
