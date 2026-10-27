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
import io.fireflysoftware.core.accounting.intefaces.dtos.journal.JournalEntryDTO;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface JournalEntryService {
    /**
     * Creates a new journal entry for the specified batch.
     *
     * @param batchId the unique identifier of the batch to which the journal entry belongs
     * @param dto the data transfer object containing the details of the journal entry to be created
     * @return a Mono emitting the created journal entry details wrapped in a JournalEntryDTO
     */
    Mono<JournalEntryDTO> create(UUID batchId, JournalEntryDTO dto);
    /**
     * Retrieves a journal entry identified by the specified batch ID and entry ID.
     *
     * @param batchId the unique identifier of the journal batch
     * @param entryId the unique identifier of the journal entry within the batch
     * @return a Mono emitting the JournalEntryDTO representing the journal entry,
     *         or an empty Mono if no entry is found
     */
    Mono<JournalEntryDTO> getById(UUID batchId, UUID entryId);
    /**
     * Updates an existing journal entry identified by the given batch ID and entry ID with the provided details.
     *
     * @param batchId the unique identifier of the batch containing the journal entry to be updated
     * @param entryId the unique identifier of the journal entry to be updated
     * @param dto the data transfer object containing the updated details of the journal entry
     * @return a Mono that emits the updated JournalEntryDTO upon successful update
     */
    Mono<JournalEntryDTO> update(UUID batchId, UUID entryId, JournalEntryDTO dto);
    /**
     * Deletes a specific journal entry identified by its batch ID and entry ID.
     *
     * @param batchId the identifier of the batch containing the journal entry to be deleted
     * @param entryId the identifier of the journal entry to be deleted
     * @return a reactive Mono signaling completion when the deletion operation has finished
     */
    Mono<Void> delete(UUID batchId, UUID entryId);
    /**
     * Searches for journal entries within a specific batch using the provided filter criteria.
     *
     * @param batchId the unique identifier of the batch containing the journal entries to be searched
     * @param filterRequest the filtering criteria and parameters for searching journal entries
     * @return a Mono emitting a paginated response containing the list of matching journal entry DTOs
     */
    Mono<PaginationResponse<JournalEntryDTO>> search(UUID batchId, FilterRequest<JournalEntryDTO> filterRequest);
}
