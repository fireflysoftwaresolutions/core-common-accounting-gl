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


package io.fireflysoftware.core.accounting.core.services.journal;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.accounting.intefaces.dtos.journal.JournalBatchDTO;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface JournalBatchService {
    /**
     * Creates a new Journal Batch based on the provided details.
     *
     * @param dto the data transfer object containing the details for the Journal Batch to be created
     * @return a Mono emitting the created Journal Batch details wrapped in a JournalBatchDTO
     */
    Mono<JournalBatchDTO> create(JournalBatchDTO dto);
    /**
     * Retrieves a journal batch by its unique identifier.
     *
     * @param id the unique identifier of the journal batch to be retrieved
     * @return a Mono emitting a JournalBatchDTO representing the journal batch, or empty if not found
     */
    Mono<JournalBatchDTO> getById(UUID id);
    /**
     * Updates an existing Journal Batch identified by the provided ID with the given details.
     *
     * @param id the unique identifier of the Journal Batch to update
     * @param dto the data transfer object containing the updated details of the Journal Batch
     * @return a Mono containing the updated Journal Batch data transfer object
     */
    Mono<JournalBatchDTO> update(UUID id, JournalBatchDTO dto);
    /**
     * Deletes a journal batch by its unique identifier.
     *
     * @param id the unique identifier of the journal batch to be deleted
     * @return a reactive Mono signaling completion when the deletion operation has finished
     */
    Mono<Void> delete(UUID id);
    /**
     * Searches for journal batches using the specified filtering criteria.
     *
     * @param filterRequest the filtering criteria and parameters for searching journal batches
     * @return a Mono emitting a paginated response containing a list of matching journal batch DTOs
     */
    Mono<PaginationResponse<JournalBatchDTO>> search(FilterRequest<JournalBatchDTO> filterRequest);
}
