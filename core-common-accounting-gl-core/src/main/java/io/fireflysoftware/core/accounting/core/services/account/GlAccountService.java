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


package io.fireflysoftware.core.accounting.core.services.account;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.accounting.intefaces.dtos.account.GlAccountDTO;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface GlAccountService {
    /**
     * Creates a new General Ledger (GL) account based on the provided details.
     *
     * @param dto the data transfer object containing the details of the GL account to be created
     * @return a Mono emitting the created GL account details
     */
    Mono<GlAccountDTO> create(GlAccountDTO dto);

    /**
     * Retrieves a general ledger account by its unique identifier.
     *
     * @param id the unique identifier of the general ledger account to be retrieved
     * @return a Mono emitting a GlAccountDTO representing the general ledger account, or empty if not found
     */
    Mono<GlAccountDTO> getById(UUID id);

    /**
     * Updates an existing General Ledger (GL) account identified by the provided ID with the given details.
     *
     * @param id The unique identifier of the GL account to update.
     * @param dto The data transfer object containing the updated details of the GL account.
     * @return A Mono containing the updated GL account data transfer object.
     */
    Mono<GlAccountDTO> update(UUID id, GlAccountDTO dto);

    /**
     * Deletes a GL account by its unique identifier.
     *
     * @param id the unique identifier of the GL account to be deleted
     * @return a reactive Mono signaling completion when the deletion operation has finished
     */
    Mono<Void> delete(UUID id);

    /**
     * Searches for GL accounts using the specified filter criteria.
     *
     * @param filterRequest the filtering criteria and parameters for searching GL accounts
     * @return a Mono emitting a paginated response containing the list of matching GL accounts
     */
    Mono<PaginationResponse<GlAccountDTO>> search(FilterRequest<GlAccountDTO> filterRequest);

    /**
     * Finds child General Ledger (GL) accounts under the specified parent GL account, applying the given filter criteria.
     *
     * @param parentId the ID of the parent GL account whose child accounts are to be retrieved
     * @param filterRequest the filter criteria to apply during the retrieval of child GL accounts
     * @return a reactive Mono containing a paginated response of GL account DTOs that represent the child accounts
     */
    Mono<PaginationResponse<GlAccountDTO>> findChildren(UUID parentId, FilterRequest<GlAccountDTO> filterRequest);

    /**
     * Creates a child General Ledger (GL) account under a specified parent account.
     *
     * @param parentId the ID of the parent GL account under which the child account will be created
     * @param dto the data transfer object containing information about the child GL account to be created
     * @return a Mono emitting the created child GL account details wrapped in a GlAccountDTO
     */
    Mono<GlAccountDTO> createChild(UUID parentId, GlAccountDTO dto);
}