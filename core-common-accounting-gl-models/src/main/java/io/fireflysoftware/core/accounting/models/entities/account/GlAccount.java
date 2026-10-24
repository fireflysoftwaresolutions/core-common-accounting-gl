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


package io.fireflysoftware.core.accounting.models.entities.account;

import io.fireflysoftware.core.accounting.intefaces.enums.account.GlAccountTypeEnum;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("gl_account")
public class GlAccount {
    @Id
    private UUID id;

    private String code;
    private String name;
    private GlAccountTypeEnum type;
    private UUID parentId;
    private boolean isLeaf;
    private boolean active;
    private String currency;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
