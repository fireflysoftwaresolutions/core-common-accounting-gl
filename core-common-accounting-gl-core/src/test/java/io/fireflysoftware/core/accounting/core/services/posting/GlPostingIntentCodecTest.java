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

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.*;

import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

class GlPostingIntentCodecTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static JsonNode vector;
    static String original, tenant, book;

    @BeforeAll
    static void fixture() throws Exception {
        try (var stream =
                GlPostingIntentCodecTest.class.getResourceAsStream(
                        "/accounting/posting-intent-v1-golden.json")) {
            assertThat(stream).isNotNull();
            vector = JSON.readTree(stream);
        }
        original = vector.get("requestJson").textValue();
        tenant = vector.get("tenantId").textValue();
        book = vector.get("bookId").textValue();
    }

    static String mutate(Consumer<ObjectNode> change) throws Exception {
        var tree = (ObjectNode) JSON.readTree(original);
        change.accept(tree);
        return JSON.writeValueAsString(tree);
    }

    static ObjectNode source(ObjectNode body) {
        return (ObjectNode) body.get("source");
    }

    static ObjectNode entry(ObjectNode body) {
        return (ObjectNode) body.get("entries").get(0);
    }

    static ObjectNode line(ObjectNode body) {
        return (ObjectNode) entry(body).get("lines").get(0);
    }

    static ObjectNode currency(ObjectNode body) {
        return (ObjectNode) line(body).get("currency");
    }

    static void rejected(String body) {
        assertThatThrownBy(() -> GlPostingIntentCodec.decode(tenant, book, body))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode().value()).isEqualTo(400);
                            assertThat(error.getReason())
                                    .doesNotContain("Golden", "object-1", "accountId");
                        });
    }

    @Test
    void decodesFullIndependentUtf8VectorAndRetainsOriginalBytes() {
        var decoded = GlPostingIntentCodec.decode(tenant, book, original);
        var actual = decoded.validated();
        assertThat(decoded.originalJson().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(original.getBytes(StandardCharsets.UTF_8));
        assertThat(actual.canonicalIntent()).isEqualTo(vector.get("canonicalIntent").textValue());
        assertThat(actual.intentDigest()).isEqualTo(vector.get("intentDigest").textValue());
        assertThat(actual.canonicalEconomicIntent())
                .isEqualTo(vector.get("canonicalEconomicIntent").textValue());
        assertThat(actual.economicIntentDigest())
                .isEqualTo(vector.get("economicIntentDigest").textValue());
        assertThat(actual.request().entries().getFirst().lines().getFirst().amountMinor())
                .isEqualTo("9007199254740993");
    }

    @Test
    void roundTripRetainsSemanticIntentAcrossJsonFormattingAndEscaping() throws Exception {
        var pretty =
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(original));
        var a = GlPostingIntentCodec.decode(tenant, book, original);
        var b = GlPostingIntentCodec.decode(tenant, book, pretty);
        assertThat(a.validated()).isEqualTo(b.validated());
        assertThat(b.originalJson()).isEqualTo(pretty).isNotEqualTo(original);
        var encoded = GlPostingIntentCodec.encode(tenant, book, a.validated().request());
        assertThat(GlPostingIntentCodec.decode(tenant, book, encoded).validated())
                .isEqualTo(a.validated());
    }

    @Test
    void requiresEveryKnownFieldAndRejectsUnknownOrNullAtEveryObjectDepth() throws Exception {
        var selectors =
                List.<java.util.function.Function<ObjectNode, ObjectNode>>of(
                        b -> b,
                        GlPostingIntentCodecTest::source,
                        GlPostingIntentCodecTest::entry,
                        GlPostingIntentCodecTest::line,
                        GlPostingIntentCodecTest::currency);
        for (var selector : selectors) {
            var keys = new ArrayList<String>();
            selector.apply((ObjectNode) JSON.readTree(original))
                    .fieldNames()
                    .forEachRemaining(keys::add);
            for (var key : keys) {
                rejected(mutate(body -> selector.apply(body).remove(key)));
                rejected(mutate(body -> selector.apply(body).putNull(key)));
            }
            rejected(mutate(body -> selector.apply(body).put("unapprovedExtraField", "denied")));
        }
    }

    @Test
    void duplicateRootNestedAndEscapedEquivalentKeysAreNotSilentlyCollapsed() {
        rejected(
                original.replace(
                        "\"expectedBookVersion\":1",
                        "\"expectedBookVersion\":1,\"expectedBookVersion\":1"));
        rejected(
                original.replace(
                        "\"owner\":\"MANUAL_ADJUSTMENT\"",
                        "\"owner\":\"MANUAL_ADJUSTMENT\",\"owner\":\"MANUAL_ADJUSTMENT\""));
        rejected(
                original.replace(
                        "\"amountMinor\":\"9007199254740993\"",
                        "\"amountMinor\":\"9007199254740993\",\"amountMinor\":\"9007199254740993\""));
        String escaped = "\\" + "u0061";
        rejected(
                original.replace(
                        "\"actionId\":",
                        "\""
                                + escaped
                                + "ctionId\":\"00000000-0000-4000-8000-000000000003\",\"actionId\":"));
    }

    @Test
    void exactIntegerTokensRejectCoercionExponentFractionOverflowAndNegativeZero() {
        for (var bad : List.of("\"1\"", "1.0", "1e0", "true", "null", "9223372036854775808", "-0"))
            rejected(
                    original.replace(
                            "\"expectedBookVersion\":1", "\"expectedBookVersion\":" + bad));
        for (var bad : List.of("\"2\"", "2.0", "2e0", "2147483648"))
            rejected(original.replace("\"postedScale\":2", "\"postedScale\":" + bad));
    }

    @Test
    void legitimatePreEpochZeroAndFullUtcBoundsRemainRepresentable() throws Exception {
        var body =
                mutate(
                        b -> {
                            b.put("sourceOccurrenceEpochMillis", GlPostingRules.MIN_TIME);
                            source(b).put("observedAtEpochMillis", GlPostingRules.MIN_TIME);
                            b.put("notAfterEpochMillis", 0);
                        });
        var actual = GlPostingIntentCodec.decode(tenant, book, body).validated().request();
        assertThat(actual.sourceOccurrenceEpochMillis()).isEqualTo(GlPostingRules.MIN_TIME);
        assertThat(actual.notAfterEpochMillis()).isZero();
        assertThat(
                        GlPostingIntentCodec.decode(
                                        tenant,
                                        book,
                                        mutate(
                                                b ->
                                                        b.put(
                                                                "notAfterEpochMillis",
                                                                GlPostingRules.MAX_TIME)))
                                .validated()
                                .request()
                                .notAfterEpochMillis())
                .isEqualTo(GlPostingRules.MAX_TIME);
        rejected(mutate(b -> b.put("notAfterEpochMillis", GlPostingRules.MAX_TIME + 1)));
    }

    @Test
    void moneyStaysCanonicalStringAcrossAllNumericAndTextAlternatives() throws Exception {
        for (var bad :
                List.of(
                        "\"0\"",
                        "\"01\"",
                        "\"+1\"",
                        "\"1.0\"",
                        "\"1e0\"",
                        "\" 1\"",
                        "1",
                        "1.0",
                        "1e0",
                        "null",
                        "true",
                        "\"" + "9".repeat(39) + "\""))
            rejected(
                    original.replace(
                            "\"amountMinor\":\"9007199254740993\"", "\"amountMinor\":" + bad));
        var valid =
                mutate(
                        b -> {
                            for (var value : entry(b).withArray("lines"))
                                ((ObjectNode) value).put("amountMinor", "9".repeat(38));
                        });
        assertThat(
                        GlPostingIntentCodec.decode(tenant, book, valid)
                                .validated()
                                .totals()
                                .getFirst()
                                .debitMinor())
                .isEqualTo("9".repeat(38));
    }

    @Test
    void malformedUnicodeIsRejectedWhileValidSupplementaryCharactersStayExact() {
        for (var hex : List.of("D800", "DC00", "0000"))
            rejected(original.replace("Golden Ω🙂", "bad-" + "\\" + "u" + hex));
        rejected(original.replace("Golden Ω🙂", "bad-" + (char) 0xd800));
        rejected(original.replace("Golden Ω🙂", "bad-" + (char) 0xdc00));
        assertThat(
                        GlPostingIntentCodec.decode(tenant, book, original)
                                .validated()
                                .request()
                                .description())
                .isEqualTo("Golden Ω🙂");
    }

    @Test
    void structuralAndRequestBudgetsRejectTrailingOrOversizedBodies() throws Exception {
        rejected(original + " {}");
        rejected("[" + original + "]");
        rejected(mutate(b -> b.set("source", JSON.createArrayNode())));
        rejected(mutate(b -> b.put("description", "🙂".repeat(270000))));
        rejected(
                mutate(
                        b -> {
                            var entries = b.withArray("entries");
                            var first = entries.get(0).deepCopy();
                            for (int i = 1; i < 51; i++) entries.add(first.deepCopy());
                        }));
        rejected(
                mutate(
                        b -> {
                            var lines = entry(b).withArray("lines");
                            var first = lines.get(0).deepCopy();
                            for (int i = 2; i < 501; i++) lines.add(first.deepCopy());
                        }));
    }

    @Test
    void canonicalOrderingIsRequiredAndDuplicateBusinessKeysCannotAlias() throws Exception {
        var reversed =
                mutate(
                        b -> {
                            var lines = entry(b).withArray("lines");
                            var first = lines.remove(0);
                            lines.add(first);
                        });
        rejected(reversed);
        var decoded = GlPostingIntentCodec.decode(tenant, book, original);
        assertThat(decoded.validated().canonicalIntent())
                .isEqualTo(vector.get("canonicalIntent").textValue());
        assertThatThrownBy(() -> decoded.validated().request().entries().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        rejected(
                mutate(
                        b -> {
                            var lines = entry(b).withArray("lines");
                            ((ObjectNode) lines.get(1))
                                    .put("lineKey", lines.get(0).get("lineKey").textValue());
                        }));
        rejected(mutate(b -> currency(b).put("encodingVersion", "UNSUPPORTED_V2")));
    }

    @Test
    void invalidWireUtf8CannotBeReplacedIntoAnApprovedIntent() throws Exception {
        byte[] prefix =
                original.substring(0, original.indexOf("Golden Ω🙂"))
                        .getBytes(StandardCharsets.UTF_8);
        byte[] suffix =
                original.substring(original.indexOf("Golden Ω🙂") + "Golden Ω🙂".length())
                        .getBytes(StandardCharsets.UTF_8);
        for (byte[] invalid :
                List.of(
                        new byte[] {(byte) 0xc3, 0x28},
                        new byte[] {(byte) 0xc0, (byte) 0xaf},
                        new byte[] {(byte) 0x80},
                        new byte[] {(byte) 0xf0, (byte) 0x9f, (byte) 0x99})) {
            var bytes = new byte[prefix.length + invalid.length + suffix.length];
            System.arraycopy(prefix, 0, bytes, 0, prefix.length);
            System.arraycopy(invalid, 0, bytes, prefix.length, invalid.length);
            System.arraycopy(suffix, 0, bytes, prefix.length + invalid.length, suffix.length);
            assertThatThrownBy(() -> GlPostingIntentCodec.decode(tenant, book, bytes))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        }
        var valid = original.replace("Golden Ω🙂", "Actual replacement character " + (char) 0xfffd);
        assertThat(
                        GlPostingIntentCodec.decode(
                                        tenant, book, valid.getBytes(StandardCharsets.UTF_8))
                                .originalJson())
                .isEqualTo(valid);
    }
}
