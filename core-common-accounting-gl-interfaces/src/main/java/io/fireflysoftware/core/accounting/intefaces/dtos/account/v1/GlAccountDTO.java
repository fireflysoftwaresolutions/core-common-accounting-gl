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

package io.fireflysoftware.core.accounting.intefaces.dtos.account.v1;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.fireflysoftware.core.accounting.intefaces.enums.account.v1.GlAccountTypeEnum;

import lombok.*;

import org.fireflyframework.annotations.ValidCurrencyCode;
import org.fireflyframework.utils.annotations.FilterableId;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlAccountDTO {

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID id;

    private String code;
    private String name;
    private GlAccountTypeEnum type;

    @FilterableId private UUID parentId;

    @JsonProperty("leaf")
    private boolean isLeaf;

    private boolean active;

    @ValidCurrencyCode private String currency;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
