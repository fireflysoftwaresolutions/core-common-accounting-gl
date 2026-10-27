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

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GlPostingLine(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lineKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accountId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long expectedAccountVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currencyAdmissionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long expectedCurrencyVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) GlCurrencyReference currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer postedScale,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String side,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[1-9][0-9]{0,37}")
                String amountMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String memo) {}
