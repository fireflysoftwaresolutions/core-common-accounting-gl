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

package io.fireflysoftware.core.accounting.intefaces.dtos.posting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Transport failure information, never a no-effect or automatic-retry receipt. */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GlPostingErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {
                "GL_POSTING_REQUEST_FAILED", "GL_POSTING_COMMIT_KNOWN_RECOVERY_REQUIRED",
                "GL_POSTING_OUTCOME_UNCERTAIN_RECOVERY_REQUIRED"}) String code,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                description = "Original action reference when explicitly supplied by the owner; never a new action")
                UUID actionReference) {}
