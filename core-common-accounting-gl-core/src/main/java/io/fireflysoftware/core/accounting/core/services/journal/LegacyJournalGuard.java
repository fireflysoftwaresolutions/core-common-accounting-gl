/*
 * Copyright 2026 Firefly Software Foundation
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

import io.r2dbc.spi.R2dbcException;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface LegacyJournalGuard {
    Mono<Void> batch(UUID batchId, boolean writable);

    Mono<Void> entry(UUID batchId, UUID entryId, boolean writable);

    Mono<Void> account(UUID accountId);

    public static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Journal resource not found");
    }

    public static ResponseStatusException conflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Legacy journal is a draft; use atomic accounting posting for financial effects");
    }

    public static Throwable translate(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++, current = current.getCause()) {
            if (current instanceof R2dbcException databaseError
                    && "23514".equals(databaseError.getSqlState())
                    && databaseError.getMessage() != null
                    && (databaseError.getMessage().contains("GL_LEGACY_")
                            || databaseError.getMessage().startsWith("GL_NATIVE_ACCOUNT:"))) {
                return conflict();
            }
        }
        return error;
    }
}
