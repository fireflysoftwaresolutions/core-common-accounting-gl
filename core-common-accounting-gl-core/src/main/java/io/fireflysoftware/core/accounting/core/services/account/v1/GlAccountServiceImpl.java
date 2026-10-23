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

package io.fireflysoftware.core.accounting.core.services.account.v1;

import io.fireflysoftware.core.accounting.core.mappers.account.v1.GlAccountMapper;
import io.fireflysoftware.core.accounting.core.services.journal.v1.LegacyJournalGuard;
import io.fireflysoftware.core.accounting.intefaces.dtos.account.v1.GlAccountDTO;
import io.fireflysoftware.core.accounting.models.entities.account.v1.GlAccount;
import io.fireflysoftware.core.accounting.models.repositories.account.v1.GlAccountRepository;

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
public class GlAccountServiceImpl implements GlAccountService {

    @Autowired private GlAccountRepository repository;

    @Autowired private GlAccountMapper mapper;

    @Autowired private LegacyJournalGuard guard;

    @Override
    public Mono<GlAccountDTO> create(GlAccountDTO dto) {
        return Mono.defer(
                        () -> {
                            var entity = mapper.toEntity(dto);
                            entity.setId(null);
                            entity.setCreatedAt(java.time.LocalDateTime.now());
                            entity.setUpdatedAt(entity.getCreatedAt());
                            return repository.save(entity).map(mapper::toDTO);
                        })
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<GlAccountDTO> getById(UUID id) {
        return repository
                .findById(id)
                .map(mapper::toDTO)
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()));
    }

    @Override
    public Mono<GlAccountDTO> update(UUID id, GlAccountDTO dto) {
        return guard.account(id)
                .then(repository.findById(id))
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()))
                .flatMap(
                        existing -> {
                            existing.setCode(dto.getCode());
                            existing.setName(dto.getName());
                            existing.setType(dto.getType());
                            existing.setParentId(dto.getParentId());
                            existing.setLeaf(dto.isLeaf());
                            existing.setActive(dto.isActive());
                            existing.setCurrency(dto.getCurrency());
                            existing.setUpdatedAt(java.time.LocalDateTime.now());
                            return repository.save(existing);
                        })
                .map(mapper::toDTO)
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<Void> delete(UUID id) {
        return guard.account(id)
                .then(repository.findById(id))
                .switchIfEmpty(Mono.error(LegacyJournalGuard.missing()))
                .flatMap(repository::delete)
                .onErrorMap(LegacyJournalGuard::translate);
    }

    @Override
    public Mono<PaginationResponse<GlAccountDTO>> search(
            FilterRequest<GlAccountDTO> filterRequest) {
        return FilterUtils.createFilter(GlAccount.class, mapper::toDTO).filter(filterRequest);
    }

    @Override
    public Mono<PaginationResponse<GlAccountDTO>> findChildren(
            UUID parentId, FilterRequest<GlAccountDTO> filterRequest) {
        filterRequest.getFilters().setParentId(parentId);
        return FilterUtils.createFilter(GlAccount.class, mapper::toDTO).filter(filterRequest);
    }

    @Override
    public Mono<GlAccountDTO> createChild(UUID parentId, GlAccountDTO dto) {
        dto.setParentId(parentId);
        return create(dto);
    }
}
