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

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GlPostingReceipt(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID receiptId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID actionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID postingId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long journalSequence,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String intentDigest,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean recovered,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) java.time.Instant recordedAt) {}
