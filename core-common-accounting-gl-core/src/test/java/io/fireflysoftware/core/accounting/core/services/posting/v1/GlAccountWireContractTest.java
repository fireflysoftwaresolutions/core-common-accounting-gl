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

package io.fireflysoftware.core.accounting.core.services.posting.v1;

import static org.assertj.core.api.Assertions.*;

import io.fireflysoftware.core.accounting.intefaces.dtos.account.v1.GlAccountDTO;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class GlAccountWireContractTest {
    @Test
    void nativeJacksonReadsThePublishedLeafFieldWithoutRequiringIsLeaf() {
        var mapper = JsonMapper.builder().build();
        for (var expected : new boolean[] {true, false}) {
            var value =
                    mapper.readValue(
                            "{\"code\":\"SYNTHETIC\",\"name\":\"Synthetic unused"
                                + " node\",\"type\":\"ASSET\",\"leaf\":"
                                    + expected
                                    + ",\"active\":true,\"currency\":\"USD\"}",
                            GlAccountDTO.class);
            assertThat(value.isLeaf()).isEqualTo(expected);
            var wire = mapper.valueToTree(value);
            assertThat(wire.path("leaf").booleanValue()).isEqualTo(expected);
            assertThat(wire.has("isLeaf")).isFalse();
        }
    }
}
