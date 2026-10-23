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

package io.fireflysoftware.core.accounting.core.services.journal.v1;

import io.r2dbc.spi.R2dbcException;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public final class LegacyJournalGuard {
    private final DatabaseClient database;

    public LegacyJournalGuard(DatabaseClient database) {
        this.database = database;
    }

    public Mono<Void> batch(UUID batchId, boolean writable) {
        return database.sql(
                        """
                        SELECT b.status::text status,
                               EXISTS (SELECT 1 FROM journal_entry e WHERE e.batch_id=b.id AND e.posted) posted
                        FROM journal_batch b WHERE b.id=:id
                        """)
                .bind("id", batchId)
                .map(
                        (row, metadata) -> {
                            if (writable
                                    && (!"OPEN".equals(row.get("status", String.class))
                                            || Boolean.TRUE.equals(
                                                    row.get("posted", Boolean.class)))) {
                                throw conflict();
                            }
                            return true;
                        })
                .one()
                .switchIfEmpty(Mono.error(missing()))
                .then();
    }

    public Mono<Void> entry(UUID batchId, UUID entryId, boolean writable) {
        return database.sql(
                        """
                        SELECT b.status::text status,e.posted
                        FROM journal_entry e JOIN journal_batch b ON b.id=e.batch_id
                        WHERE e.id=:entry AND b.id=:batch
                        """)
                .bind("entry", entryId)
                .bind("batch", batchId)
                .map(
                        (row, metadata) -> {
                            if (writable
                                    && (!"OPEN".equals(row.get("status", String.class))
                                            || Boolean.TRUE.equals(
                                                    row.get("posted", Boolean.class)))) {
                                throw conflict();
                            }
                            return true;
                        })
                .one()
                .switchIfEmpty(Mono.error(missing()))
                .then();
    }

    public Mono<Void> account(UUID accountId) {
        return database.sql(
                        """
                        SELECT EXISTS (
                            SELECT 1 FROM journal_line l JOIN journal_entry e ON e.id=l.entry_id
                            JOIN journal_batch b ON b.id=e.batch_id
                            WHERE l.account_id=a.id AND (e.posted OR b.status<>'OPEN')
                        ) historical FROM gl_account a WHERE a.id=:id
                        """)
                .bind("id", accountId)
                .map(
                        (row, metadata) -> {
                            if (Boolean.TRUE.equals(row.get("historical", Boolean.class)))
                                throw conflict();
                            return true;
                        })
                .one()
                .switchIfEmpty(Mono.error(missing()))
                .then();
    }

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
