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

package io.fireflysoftware.core.accounting.models.repositories.posting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

import java.util.Map;

class GlNativeMappingTest {
    static final String PACKAGE = "io.fireflysoftware.core.accounting.models.entities.posting.";

    static Class<?> entity(String name) {
        return assertDoesNotThrow(
                () -> Class.forName(PACKAGE + name), "Native mapped entity is missing");
    }

    @Test
    void everyNativeRowHasItsExactTableMapping() {
        Map.ofEntries(
                        Map.entry("GlNativeBook", "gl_native_book"),
                        Map.entry("GlNativeCurrencyAdmission", "gl_native_currency_admission"),
                        Map.entry("GlNativeAccount", "gl_native_account"),
                        Map.entry("GlNativePeriod", "gl_native_period"),
                        Map.entry("GlNativeMapping", "gl_native_mapping"),
                        Map.entry("GlNativeBookSequence", "gl_native_book_sequence"),
                        Map.entry("GlNativeJournalAction", "gl_native_journal_action"),
                        Map.entry("GlNativePosting", "gl_native_posting"),
                        Map.entry("GlNativeEntry", "gl_native_entry"),
                        Map.entry("GlNativeLine", "gl_native_line"),
                        Map.entry("GlNativeJournalEvent", "gl_native_journal_event"))
                .forEach(
                        (name, table) ->
                                assertEquals(
                                        table, entity(name).getAnnotation(Table.class).value()));
    }

    @Test
    void nativeMappingsExplicitlyTargetTheProtectedPublicSchema() {
        for (var name :
                new String[] {
                    "GlNativeBook",
                    "GlNativeCurrencyAdmission",
                    "GlNativeAccount",
                    "GlNativePeriod",
                    "GlNativeMapping",
                    "GlNativeBookSequence",
                    "GlNativeJournalAction",
                    "GlNativePosting",
                    "GlNativeEntry",
                    "GlNativeLine",
                    "GlNativeJournalEvent"
                }) assertEquals("public", entity(name).getAnnotation(Table.class).schema());
    }

    @Test
    void entryLineAndEventIdsAreRealEmbeddedCompositeIds() {
        for (var name : new String[] {"GlNativeEntry", "GlNativeLine", "GlNativeJournalEvent"}) {
            var id = assertDoesNotThrow(() -> entity(name).getDeclaredField("id"));
            assertNotNull(id.getAnnotation(Id.class));
            assertNotNull(
                    org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                            id, Embedded.class));
            assertTrue(id.getType().isRecord());
        }
    }

    @Test
    void generatedAndNativeIdentityColumnsAreNotApplicationFields() {
        var action = entity("GlNativeJournalAction");
        for (var name : new String[] {"requestDocument", "ownerXid"})
            assertThrows(NoSuchFieldException.class, () -> action.getDeclaredField(name));
        var recordedAt = assertDoesNotThrow(() -> action.getDeclaredField("recordedAt"));
        assertNotNull(
                recordedAt.getAnnotation(
                        org.springframework.data.annotation.ReadOnlyProperty.class));
        assertThrows(
                NoSuchFieldException.class,
                () -> entity("GlNativeLine").getDeclaredField("amountValue"));
        assertDoesNotThrow(() -> action.getDeclaredField("originalRequest"));
        assertDoesNotThrow(() -> action.getDeclaredField("requestShape"));
    }
}
