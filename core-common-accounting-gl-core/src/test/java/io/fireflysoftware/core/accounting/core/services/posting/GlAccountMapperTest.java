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

import static org.assertj.core.api.Assertions.*;

import io.fireflysoftware.core.accounting.core.mappers.account.GlAccountMapper;
import io.fireflysoftware.core.accounting.intefaces.dtos.account.GlAccountDTO;
import io.fireflysoftware.core.accounting.models.entities.account.GlAccount;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class GlAccountMapperTest {
    @Test
    void explicitLeafAndControlValuesSurviveBothMappingDirections() {
        var mapper = Mappers.getMapper(GlAccountMapper.class);
        for (var leaf : new boolean[] {true, false}) {
            assertThat(mapper.toEntity(GlAccountDTO.builder().isLeaf(leaf).build()).isLeaf())
                    .isEqualTo(leaf);
            assertThat(mapper.toDTO(GlAccount.builder().isLeaf(leaf).build()).isLeaf())
                    .isEqualTo(leaf);
        }
    }
}
