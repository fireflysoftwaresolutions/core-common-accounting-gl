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

import io.fireflysoftware.core.accounting.intefaces.dtos.posting.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

public final class GlPostingRules {
    public static final String INTENT_ENCODING = "GL_POSTING_INTENT_V1";
    public static final long MIN_TIME = -2208988800000L;
    public static final long MAX_TIME = 253402300799999L;
    public static final BigInteger MAX_MINOR = BigInteger.TEN.pow(38).subtract(BigInteger.ONE);
    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private GlPostingRules() {}

    public record Validated(
            GlPostingRequest request,
            String canonicalIntent,
            String intentDigest,
            String canonicalEconomicIntent,
            String economicIntentDigest,
            String sourceClaimKey,
            List<GlCurrencyTotal> totals) {}

    public static Validated validate(String tenantId, String bookId, GlPostingRequest input) {
        try {
            uuid(tenantId);
            uuid(bookId);
            if (input == null) throw bad();
            uuid(input.actionId());
            uuid(input.periodId());
            version(input.expectedBookVersion(), false);
            version(input.expectedPeriodVersion(), false);
            time(input.notAfterEpochMillis());
            date(input.businessDate());
            time(input.sourceOccurrenceEpochMillis());
            var source = input.source();
            if (source == null) throw bad();
            text(source.owner(), 100);
            text(source.objectReference(), 255);
            text(source.effectReference(), 255);
            version(source.revision(), true);
            digestField(source.digest());
            text(source.eventKind(), 100);
            if (source.effectOrdinal() == null || source.effectOrdinal() < 0) throw bad();
            text(source.financialReceiptReference(), 500);
            text(source.provenanceReference(), 500);
            time(source.observedAtEpochMillis());
            uuid(input.mappingId());
            version(input.mappingVersion(), true);
            digestField(input.mappingDigest());
            text(input.description(), 500);
            if (input.entries() == null || input.entries().isEmpty() || input.entries().size() > 50)
                throw bad();
            int lineCount = 0;
            String priorEntry = null;
            var totals = new TreeMap<String, Amounts>();
            var admissions = new HashMap<String, String>();
            var accounts = new HashMap<String, String>();
            var frozenEntries = new ArrayList<GlPostingEntry>();
            for (var entry : input.entries()) {
                if (entry == null) throw bad();
                key(entry.entryKey());
                text(entry.description(), 500);
                if (priorEntry != null && priorEntry.compareTo(entry.entryKey()) >= 0) throw bad();
                priorEntry = entry.entryKey();
                if (entry.lines() == null || entry.lines().size() < 2) throw bad();
                lineCount = Math.addExact(lineCount, entry.lines().size());
                if (lineCount > 500) throw bad();
                String priorLine = null;
                var entryTotals = new TreeMap<String, Amounts>();
                for (var line : entry.lines()) {
                    if (line == null) throw bad();
                    key(line.lineKey());
                    if (priorLine != null && priorLine.compareTo(line.lineKey()) >= 0) throw bad();
                    priorLine = line.lineKey();
                    uuid(line.accountId());
                    version(line.expectedAccountVersion(), false);
                    uuid(line.currencyAdmissionId());
                    version(line.expectedCurrencyVersion(), false);
                    currency(line.currency());
                    if (line.postedScale() == null
                            || line.postedScale() < 0
                            || line.postedScale() > 10) throw bad();
                    if (!Set.of("DR", "CR").contains(line.side())) throw bad();
                    var amount = minor(line.amountMinor());
                    text(line.memo(), 500);
                    var group = currencyKey(line.currency(), line.postedScale());
                    consistent(admissions, line.currencyAdmissionId(), group);
                    consistent(accounts, line.accountId(), group);
                    add(entryTotals, group, line, amount);
                    add(totals, group, line, amount);
                    if (totals.size() > 32) throw bad();
                }
                entryTotals.values().forEach(Amounts::requireBalanced);
                frozenEntries.add(
                        new GlPostingEntry(
                                entry.entryKey(), entry.description(), List.copyOf(entry.lines())));
            }
            totals.values().forEach(Amounts::requireBalanced);
            var frozen =
                    new GlPostingRequest(
                            input.actionId(),
                            input.periodId(),
                            input.expectedBookVersion(),
                            input.expectedPeriodVersion(),
                            input.notAfterEpochMillis(),
                            input.businessDate(),
                            input.sourceOccurrenceEpochMillis(),
                            source,
                            input.mappingId(),
                            input.mappingVersion(),
                            input.mappingDigest(),
                            input.description(),
                            List.copyOf(frozenEntries));
            var canonical = canonical(INTENT_ENCODING, fields(tenantId, bookId, frozen, true));
            var economic =
                    canonical(
                            "GL_POSTING_ECONOMIC_INTENT_V1",
                            fields(tenantId, bookId, frozen, false));
            var sourceKey =
                    canonical(
                            "GL_SOURCE_EFFECT_IDENTITY_V1",
                            List.of(
                                    tenantId,
                                    bookId,
                                    source.owner(),
                                    source.objectReference(),
                                    source.effectReference(),
                                    source.revision().toString(),
                                    source.eventKind(),
                                    source.effectOrdinal().toString()));
            return new Validated(
                    frozen,
                    canonical,
                    digest(canonical),
                    economic,
                    digest(economic),
                    digest(sourceKey),
                    totals.values().stream().map(Amounts::view).toList());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw bad();
        }
    }

    private static void consistent(Map<String, String> values, String id, String value) {
        var previous = values.putIfAbsent(id, value);
        if (previous != null && !previous.equals(value)) throw bad();
    }

    private static void add(
            Map<String, Amounts> totals, String group, GlPostingLine line, BigInteger amount) {
        var total =
                totals.computeIfAbsent(
                        group, ignored -> new Amounts(line.currency(), line.postedScale()));
        if (line.side().equals("DR")) total.debit = bounded(total.debit.add(amount));
        else total.credit = bounded(total.credit.add(amount));
    }

    private static final class Amounts {
        private final GlCurrencyReference currency;
        private final int scale;
        private BigInteger debit = BigInteger.ZERO, credit = BigInteger.ZERO;

        private Amounts(GlCurrencyReference currency, int scale) {
            this.currency = currency;
            this.scale = scale;
        }

        private void requireBalanced() {
            if (!debit.equals(credit) || debit.signum() <= 0) throw bad();
        }

        private GlCurrencyTotal view() {
            return new GlCurrencyTotal(currency, scale, debit.toString(), credit.toString());
        }
    }

    public static BigInteger minor(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,37}")) throw bad();
        return bounded(new BigInteger(value));
    }

    public static BigInteger bounded(BigInteger value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_MINOR) > 0) throw bad();
        return value;
    }

    public static void uuid(String value) {
        if (value == null
                || !value.matches(UUID_PATTERN)
                || value.equals("00000000-0000-0000-0000-000000000000")) throw bad();
    }

    public static void digestField(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw bad();
    }

    public static void version(Long value, boolean positive) {
        if (value == null || value < (positive ? 1 : 0)) throw bad();
    }

    public static void time(Long value) {
        if (value == null || value < MIN_TIME || value > MAX_TIME) throw bad();
    }

    public static LocalDate date(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw bad();
            var parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1900 || parsed.getYear() > 9999) throw bad();
            return parsed;
        } catch (RuntimeException e) {
            throw bad();
        }
    }

    public static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.indexOf(0) >= 0)
            throw bad();
        scalar(value);
    }

    private static void scalar(String value) {
        if (value == null || value.indexOf(0) >= 0) throw bad();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw bad();
            } else if (Character.isLowSurrogate(current)) throw bad();
        }
    }

    public static void key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) throw bad();
    }

    public static void currency(GlCurrencyReference value) {
        if (value == null) throw bad();
        uuid(value.policyId());
        uuid(value.currencyId());
        if (value.isoCode() == null || !value.isoCode().matches("[A-Z]{3}")) throw bad();
        version(value.version(), true);
        digestField(value.digest());
        if (!"MASTERS_CURRENCY_POLICY_V1".equals(value.encodingVersion())) throw bad();
    }

    private static String currencyKey(GlCurrencyReference value, int scale) {
        var fields = new ArrayList<String>();
        currencyFields(fields, value);
        fields.add(Integer.toString(scale));
        return canonical("GL_CURRENCY_REPRESENTATION_V1", fields);
    }

    private static List<String> fields(
            String tenant, String book, GlPostingRequest r, boolean includeAction) {
        var f = new ArrayList<String>();
        f.add(tenant);
        f.add(book);
        if (includeAction) f.add(r.actionId());
        f.add(r.periodId());
        f.add(r.expectedBookVersion().toString());
        f.add(r.expectedPeriodVersion().toString());
        f.add(r.notAfterEpochMillis().toString());
        f.add(r.businessDate());
        f.add(r.sourceOccurrenceEpochMillis().toString());
        var s = r.source();
        f.addAll(
                List.of(
                        s.owner(),
                        s.objectReference(),
                        s.effectReference(),
                        s.revision().toString(),
                        s.digest(),
                        s.eventKind(),
                        s.effectOrdinal().toString(),
                        s.financialReceiptReference(),
                        s.provenanceReference(),
                        s.observedAtEpochMillis().toString()));
        f.add(r.mappingId());
        f.add(r.mappingVersion().toString());
        f.add(r.mappingDigest());
        f.add(r.description());
        f.add(Integer.toString(r.entries().size()));
        for (var entry : r.entries()) {
            f.add(entry.entryKey());
            f.add(entry.description());
            f.add(Integer.toString(entry.lines().size()));
            for (var line : entry.lines()) {
                f.addAll(
                        List.of(
                                line.lineKey(),
                                line.accountId(),
                                line.expectedAccountVersion().toString(),
                                line.currencyAdmissionId(),
                                line.expectedCurrencyVersion().toString()));
                currencyFields(f, line.currency());
                f.addAll(
                        List.of(
                                line.postedScale().toString(),
                                line.side(),
                                line.amountMinor(),
                                line.memo()));
            }
        }
        return f;
    }

    private static void currencyFields(List<String> f, GlCurrencyReference c) {
        f.addAll(
                List.of(
                        c.policyId(),
                        c.currencyId(),
                        c.isoCode(),
                        c.version().toString(),
                        c.digest(),
                        c.encodingVersion()));
    }

    public static String canonical(String encoding, List<String> fields) {
        text(encoding, 100);
        var output = new StringBuilder(encoding).append('\n');
        for (var field : fields) {
            scalar(field);
            output.append(field.getBytes(StandardCharsets.UTF_8).length)
                    .append(':')
                    .append(field)
                    .append('\n');
        }
        return output.toString();
    }

    public static String digest(String value) {
        scalar(value);
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ResponseStatusException bad() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Exact bounded balanced accounting intent required");
    }
}
