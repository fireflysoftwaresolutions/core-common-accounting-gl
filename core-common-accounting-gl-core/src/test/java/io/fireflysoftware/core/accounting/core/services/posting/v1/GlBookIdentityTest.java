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

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class GlBookIdentityTest {
    @Test
    void versionedOwnerNamespaceAndCrossLanguageVectorsRemainPinned() {
        assertThat(GlBookIdentity.NAMESPACE.toString())
                .isEqualTo("d28580cc-de78-53fd-99c9-2f1dd076c546");
        assertThat(
                        GlBookIdentity.id(
                                "12345678-1234-4234-8234-123456789abc",
                                "87654321-4321-4321-8321-cba987654321"))
                .isEqualTo("bfd52351-017a-5b97-bf18-3a8cfad28120");
        assertThat(
                        GlBookIdentity.id(
                                "12345678-1234-4234-8234-123456789abc",
                                "87654321-4321-4321-8321-cba987654322"))
                .isEqualTo("89996a70-0135-5062-b42f-40ba42d75115");
        assertThat(
                        GlBookIdentity.id(
                                "12345678-1234-4234-8234-123456789abd",
                                "87654321-4321-4321-8321-cba987654321"))
                .isEqualTo("83b7b528-02cf-5d2b-90f2-84b1de4903dc");
    }

    @Test
    void nonCanonicalOrPlaceholderInputNeverCreatesAReplacementIdentity() {
        for (String tenant :
                new String[] {
                    null,
                    "",
                    "00000000-0000-0000-0000-000000000000",
                    "12345678-1234-4234-8234-123456789ABC"
                })
            assertThatThrownBy(
                            () -> GlBookIdentity.id(tenant, "87654321-4321-4321-8321-cba987654321"))
                    .isInstanceOf(ResponseStatusException.class);
    }
}
