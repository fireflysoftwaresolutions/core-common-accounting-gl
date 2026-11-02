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

import io.fireflysoftware.core.accounting.intefaces.dtos.posting.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

class GlPostingRulesTest {
    private static final String TENANT = UUID.randomUUID().toString(),
            BOOK = UUID.randomUUID().toString();
    private static final GlCurrencyReference USD =
            new GlCurrencyReference(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    "USD",
                    1L,
                    "a".repeat(64),
                    "MASTERS_CURRENCY_POLICY_V1");
    private static final String ADMISSION = UUID.randomUUID().toString();

    private static GlPostingLine line(
            String key, String side, String amount, GlCurrencyReference currency, int scale) {
        return new GlPostingLine(
                key,
                UUID.randomUUID().toString(),
                0L,
                (currency.equals(USD)
                        ? ADMISSION
                        : UUID.nameUUIDFromBytes(
                                        currency.policyId()
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .toString()),
                0L,
                currency,
                scale,
                side,
                amount,
                "Synthetic exact line");
    }

    private static GlPostingRequest request(List<GlPostingEntry> entries) {
        return new GlPostingRequest(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                0L,
                0L,
                1792886400000L,
                "2026-10-21",
                1792537200000L,
                new GlSourceReference(
                        "MANUAL_ADJUSTMENT",
                        "synthetic-object",
                        "synthetic-effect",
                        1L,
                        "b".repeat(64),
                        "SYNTHETIC_ADJUSTMENT",
                        0,
                        "synthetic-receipt",
                        "synthetic-finite-approval",
                        1792537200000L),
                UUID.randomUUID().toString(),
                1L,
                "c".repeat(64),
                "Synthetic balanced journal",
                entries);
    }

    private static GlPostingEntry entry(String key, GlPostingLine... lines) {
        return new GlPostingEntry(key, "Synthetic entry", List.of(lines));
    }

    private static GlPostingRequest balanced(String amount, int scale) {
        return request(
                List.of(
                        entry(
                                "001",
                                line("001", "DR", amount, USD, scale),
                                line("002", "CR", amount, USD, scale))));
    }

    private static void bad(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void preservesExactUnitsBeyondJavascriptRangeAndAllSupportedScales() {
        for (var scale : List.of(0, 2, 3, 10)) {
            var result = GlPostingRules.validate(TENANT, BOOK, balanced("9007199254740993", scale));
            assertThat(result.totals())
                    .singleElement()
                    .satisfies(
                            total -> {
                                assertThat(total.debitMinor()).isEqualTo("9007199254740993");
                                assertThat(total.creditMinor()).isEqualTo("9007199254740993");
                                assertThat(total.postedScale()).isEqualTo(scale);
                            });
        }
        assertThat(
                        GlPostingRules.validate(TENANT, BOOK, balanced("9".repeat(38), 2))
                                .intentDigest())
                .hasSize(64);
    }

    @Test
    void invalidMinorUnitsAndOverflowAreRejectedBeforeAnyEffect() {
        for (var amount :
                Arrays.asList(
                        null, "", "0", "01", "-1", "+1", "1.0", "1e2", " 1", "1 ", "9".repeat(39)))
            bad(() -> GlPostingRules.validate(TENANT, BOOK, balanced(amount, 2)));
        for (var scale : List.of(-1, 11))
            bad(() -> GlPostingRules.validate(TENANT, BOOK, balanced("1", scale)));
        var max = "9".repeat(38);
        bad(
                () ->
                        GlPostingRules.validate(
                                TENANT,
                                BOOK,
                                request(
                                        List.of(
                                                entry(
                                                        "001",
                                                        line("001", "DR", max, USD, 2),
                                                        line("002", "DR", "1", USD, 2),
                                                        line("003", "CR", max, USD, 2),
                                                        line("004", "CR", "1", USD, 2))))));
        bad(
                () ->
                        GlPostingRules.validate(
                                TENANT,
                                BOOK,
                                request(
                                        List.of(
                                                entry(
                                                        "001",
                                                        line("001", "DR", max, USD, 2),
                                                        line("002", "CR", max, USD, 2)),
                                                entry(
                                                        "002",
                                                        line("001", "DR", "1", USD, 2),
                                                        line("002", "CR", "1", USD, 2))))));
    }

    @Test
    void entryAndRepresentationBalanceCannotBeNettedAcrossBoundaries() {
        var eur =
                new GlCurrencyReference(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "EUR",
                        1L,
                        "d".repeat(64),
                        USD.encodingVersion());
        bad(
                () ->
                        GlPostingRules.validate(
                                TENANT,
                                BOOK,
                                request(
                                        List.of(
                                                entry(
                                                        "001",
                                                        line("001", "DR", "10", USD, 2),
                                                        line("002", "CR", "10", eur, 2))))));
        bad(
                () ->
                        GlPostingRules.validate(
                                TENANT,
                                BOOK,
                                request(
                                        List.of(
                                                entry(
                                                        "001",
                                                        line("001", "DR", "10", USD, 2),
                                                        line("002", "CR", "9", USD, 2)),
                                                entry(
                                                        "002",
                                                        line("001", "DR", "9", USD, 2),
                                                        line("002", "CR", "10", USD, 2))))));
        bad(
                () ->
                        GlPostingRules.validate(
                                TENANT,
                                BOOK,
                                request(
                                        List.of(
                                                entry(
                                                        "001",
                                                        line("001", "DR", "10", USD, 2),
                                                        line("002", "CR", "10", USD, 3))))));
    }

    @Test
    void fullIntentBindsScopeDeadlineAndLineOrder() {
        var original = balanced("1", 2);
        var first = GlPostingRules.validate(TENANT, BOOK, original);
        assertThat(GlPostingRules.validate(TENANT, BOOK, original).intentDigest())
                .isEqualTo(first.intentDigest());
        assertThat(
                        GlPostingRules.validate(UUID.randomUUID().toString(), BOOK, original)
                                .intentDigest())
                .isNotEqualTo(first.intentDigest());
        var changed =
                new GlPostingRequest(
                        original.actionId(),
                        original.periodId(),
                        0L,
                        0L,
                        original.notAfterEpochMillis() + 1,
                        original.businessDate(),
                        original.sourceOccurrenceEpochMillis(),
                        original.source(),
                        original.mappingId(),
                        original.mappingVersion(),
                        original.mappingDigest(),
                        original.description(),
                        original.entries());
        assertThat(GlPostingRules.validate(TENANT, BOOK, changed).intentDigest())
                .isNotEqualTo(first.intentDigest());
        var e = original.entries().getFirst();
        bad(
                () ->
                        GlPostingRules.validate(
                                TENANT,
                                BOOK,
                                request(
                                        List.of(
                                                new GlPostingEntry(
                                                        e.entryKey(),
                                                        e.description(),
                                                        List.of(
                                                                e.lines().get(1),
                                                                e.lines().get(0)))))));
        bad(() -> GlPostingRules.validate(TENANT.toUpperCase(Locale.ROOT), BOOK, original));
    }

    @Test
    void missingAndNonScalarValuesNeverBecomeCanonicalReplacementBytes() {
        bad(() -> GlPostingRules.validate(TENANT, BOOK, null));
        bad(() -> GlPostingRules.validate(TENANT, BOOK, request(List.of())));
        var r = balanced("1", 2);
        var malformed =
                new GlPostingRequest(
                        r.actionId(),
                        r.periodId(),
                        0L,
                        0L,
                        r.notAfterEpochMillis(),
                        r.businessDate(),
                        r.sourceOccurrenceEpochMillis(),
                        r.source(),
                        r.mappingId(),
                        r.mappingVersion(),
                        r.mappingDigest(),
                        "unpaired-" + (char) 0xd800,
                        r.entries());
        bad(() -> GlPostingRules.validate(TENANT, BOOK, malformed));
        var valid =
                new GlPostingRequest(
                        r.actionId(),
                        r.periodId(),
                        0L,
                        0L,
                        r.notAfterEpochMillis(),
                        r.businessDate(),
                        r.sourceOccurrenceEpochMillis(),
                        r.source(),
                        r.mappingId(),
                        r.mappingVersion(),
                        r.mappingDigest(),
                        "Greek Ω and emoji " + new String(Character.toChars(0x1f642)),
                        r.entries());
        assertThat(GlPostingRules.validate(TENANT, BOOK, valid).intentDigest()).hasSize(64);
    }

    @Test
    void unlikeCurrencyGroupsRemainSeparateAndBounded() {
        var lines = new ArrayList<GlPostingLine>();
        for (int i = 0; i < 33; i++) {
            var currency =
                    new GlCurrencyReference(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            "AA" + (char) ('A' + i % 26),
                            1L,
                            "d".repeat(64),
                            USD.encodingVersion());
            lines.add(
                    line(
                            String.format(Locale.ROOT, "%03d", i * 2),
                            "DR",
                            "9".repeat(38),
                            currency,
                            2));
            lines.add(
                    line(
                            String.format(Locale.ROOT, "%03d", i * 2 + 1),
                            "CR",
                            "9".repeat(38),
                            currency,
                            2));
            if (i == 31)
                assertThat(
                                GlPostingRules.validate(
                                                TENANT,
                                                BOOK,
                                                request(
                                                        List.of(
                                                                new GlPostingEntry(
                                                                        "001",
                                                                        "Independent"
                                                                            + " representations",
                                                                        new ArrayList<>(lines)))))
                                        .totals())
                        .hasSize(32);
        }
        bad(
                () ->
                        GlPostingRules.validate(
                                TENANT,
                                BOOK,
                                request(
                                        List.of(
                                                new GlPostingEntry(
                                                        "001", "Too many groups", lines)))));
    }

    @Test
    void maximumEntriesAndLinesAreCheckedAndValidatedIntentCannotMutate() {
        var lines = new ArrayList<GlPostingLine>();
        for (int i = 0; i < 250; i++) {
            lines.add(line(String.format(Locale.ROOT, "%03d", i * 2), "DR", "1", USD, 2));
            lines.add(line(String.format(Locale.ROOT, "%03d", i * 2 + 1), "CR", "1", USD, 2));
        }
        var original = request(List.of(new GlPostingEntry("001", "Maximum lines", lines)));
        var validated = GlPostingRules.validate(TENANT, BOOK, original);
        lines.clear();
        assertThat(validated.request().entries().getFirst().lines()).hasSize(500);
        assertThat(GlPostingRules.validate(TENANT, BOOK, validated.request()).intentDigest())
                .isEqualTo(validated.intentDigest());
        var entries = new ArrayList<GlPostingEntry>();
        for (int i = 0; i < 51; i++) {
            entries.add(
                    entry(
                            String.format(Locale.ROOT, "%03d", i),
                            line("001", "DR", "1", USD, 2),
                            line("002", "CR", "1", USD, 2)));
            if (i == 49)
                assertThat(
                                GlPostingRules.validate(
                                                TENANT, BOOK, request(new ArrayList<>(entries)))
                                        .totals())
                        .hasSize(1);
        }
        bad(() -> GlPostingRules.validate(TENANT, BOOK, request(entries)));
        var oversized = new ArrayList<>(validated.request().entries().getFirst().lines());
        oversized.add(line("500", "DR", "1", USD, 2));
        oversized.add(line("501", "CR", "1", USD, 2));
        bad(
                () ->
                        GlPostingRules.validate(
                                TENANT,
                                BOOK,
                                request(
                                        List.of(
                                                new GlPostingEntry(
                                                        "001", "Too many lines", oversized)))));
    }

    @Test
    void canonicalHelpersRejectNonScalarUnicodeBeforeUtf8ReplacementCanOccur() {
        for (var malformed :
                List.of(
                        "invalid-" + (char) 0xd800,
                        "invalid-" + (char) 0xdc00,
                        "nul-" + (char) 0)) {
            bad(() -> GlPostingRules.canonical("GL_TEST_V1", List.of(malformed)));
            bad(() -> GlPostingRules.digest(malformed));
        }
        assertThat(GlPostingRules.canonical("GL_TEST_V1", List.of("Ω🙂")))
                .isEqualTo("GL_TEST_V1\n6:Ω🙂\n");
    }

    private static String fixedId(int value) {
        return String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", value);
    }

    @Test
    void versionedFullIntentMatchesIndependentUtf8GoldenVector() {
        var currency =
                new GlCurrencyReference(
                        fixedId(6),
                        fixedId(7),
                        "USD",
                        1L,
                        "a".repeat(64),
                        "MASTERS_CURRENCY_POLICY_V1");
        var request =
                new GlPostingRequest(
                        fixedId(3),
                        fixedId(4),
                        1L,
                        1L,
                        1792886400000L,
                        "2026-10-21",
                        1792537200000L,
                        new GlSourceReference(
                                "MANUAL_ADJUSTMENT",
                                "object-1",
                                "effect-1",
                                1L,
                                "b".repeat(64),
                                "ADJUSTMENT",
                                0,
                                "owner-effect-1",
                                "approval-1",
                                1792537200000L),
                        fixedId(5),
                        1L,
                        "c".repeat(64),
                        "Golden Ω🙂",
                        List.of(
                                new GlPostingEntry(
                                        "001",
                                        "Entry Ω🙂",
                                        List.of(
                                                new GlPostingLine(
                                                        "001",
                                                        fixedId(9),
                                                        1L,
                                                        fixedId(8),
                                                        1L,
                                                        currency,
                                                        2,
                                                        "DR",
                                                        "9007199254740993",
                                                        "Debit Ω🙂"),
                                                new GlPostingLine(
                                                        "002",
                                                        fixedId(10),
                                                        1L,
                                                        fixedId(8),
                                                        1L,
                                                        currency,
                                                        2,
                                                        "CR",
                                                        "9007199254740993",
                                                        "Credit Ω🙂")))));
        var actual = GlPostingRules.validate(fixedId(1), fixedId(2), request);
        assertThat(actual.intentDigest())
                .isEqualTo("4168acd1c4bf1854a35904b25fac238e9a66aeb1a1ab58320540db1386542f20");
        assertThat(actual.economicIntentDigest())
                .isEqualTo("74a9f8c383584895fe6a72540f2f89b8dae471b978ae7d230689279033b3e23d");
        assertThat(actual.canonicalEconomicIntent())
                .startsWith("GL_POSTING_ECONOMIC_INTENT_V1\n")
                .contains("Golden Ω🙂", "Entry Ω🙂");
        assertThat(GlPostingRules.digest(actual.canonicalEconomicIntent()))
                .isEqualTo("74a9f8c383584895fe6a72540f2f89b8dae471b978ae7d230689279033b3e23d");
        assertThat(actual.sourceClaimKey())
                .isEqualTo("ba1fb6ce46d522e7ab24e09c7aa76dda228349d58eb7f6f00269c1b1b48e1ce7");
    }
}
