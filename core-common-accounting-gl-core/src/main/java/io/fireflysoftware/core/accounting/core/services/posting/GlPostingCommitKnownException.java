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

package io.fireflysoftware.core.accounting.core.services.posting;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Commit is confirmed, but current protected delivery is unavailable. Contains no private receipt.
 */
public final class GlPostingCommitKnownException extends ResponseStatusException {
    private final UUID actionReference;

    public GlPostingCommitKnownException(UUID actionReference) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Posting committed; current protected delivery unavailable");
        this.actionReference = actionReference;
    }

    public UUID actionReference() {
        return actionReference;
    }

    public boolean commitKnown() {
        return true;
    }
}
