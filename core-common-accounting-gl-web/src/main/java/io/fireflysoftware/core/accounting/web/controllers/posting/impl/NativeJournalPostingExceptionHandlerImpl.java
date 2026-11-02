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

package io.fireflysoftware.core.accounting.web.controllers.posting.impl;

import io.fireflysoftware.core.accounting.core.services.posting.GlPostingCommitKnownException;
import io.fireflysoftware.core.accounting.core.services.posting.GlPostingOutcomeUncertainException;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.GlPostingErrorResponse;
import io.fireflysoftware.core.accounting.web.controllers.posting.NativeJournalPostingsController;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@io.swagger.v3.oas.annotations.Hidden
@RestControllerAdvice(assignableTypes = NativeJournalPostingsController.class)
public class NativeJournalPostingExceptionHandlerImpl {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<GlPostingErrorResponse> handle(Exception error) {
        int status = error instanceof ResponseStatusException known ? known.getStatusCode().value() : 500;
        if (status < 400 || status > 599) status = 500;
        String code = "GL_POSTING_REQUEST_FAILED";
        UUID action = null;
        if (error instanceof GlPostingCommitKnownException known) {
            code = "GL_POSTING_COMMIT_KNOWN_RECOVERY_REQUIRED";
            action = known.actionReference();
        } else if (error instanceof GlPostingOutcomeUncertainException uncertain) {
            code = "GL_POSTING_OUTCOME_UNCERTAIN_RECOVERY_REQUIRED";
            action = uncertain.actionReference();
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(new GlPostingErrorResponse(status, code, action));
    }
}
