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

import static org.junit.jupiter.api.Assertions.*;

import io.fireflysoftware.core.accounting.intefaces.dtos.posting.*;
import io.fireflysoftware.core.accounting.models.entities.posting.*;
import io.fireflysoftware.core.accounting.models.repositories.posting.GlNativePostingRepository;
import io.r2dbc.spi.*;

import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.*;

class GlPostingApplicationServiceTest {
    static final UUID TENANT = id(1),
            BOOK = id(2),
            ACTION = id(3),
            PERIOD = id(4),
            MAPPING = id(5),
            ACCOUNT1 = id(6),
            ACCOUNT2 = id(7),
            CURRENCY = id(8),
            POLICY = id(9),
            CURRENCY_ID = id(10);

    static UUID id(int n) {
        return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", n));
    }

    static final String POLICY_BODY = "synthetic retained currency policy";
    static final GlCurrencyReference REF =
            new GlCurrencyReference(
                    POLICY.toString(),
                    CURRENCY_ID.toString(),
                    "USD",
                    1L,
                    GlPostingRules.digest(POLICY_BODY),
                    "MASTERS_CURRENCY_POLICY_V1");

    static GlPostingRequest request(String amount, long until) {
        return new GlPostingRequest(
                ACTION.toString(),
                PERIOD.toString(),
                1L,
                1L,
                until,
                "2026-10-26",
                Instant.now().minusSeconds(1).toEpochMilli(),
                new GlSourceReference(
                        "MANUAL_ADJUSTMENT",
                        "synthetic-object",
                        "synthetic-effect",
                        1L,
                        "b".repeat(64),
                        "SYNTHETIC_ADJUSTMENT",
                        0,
                        "synthetic-receipt",
                        "synthetic-provenance",
                        Instant.now().minusSeconds(1).toEpochMilli()),
                MAPPING.toString(),
                1L,
                "c".repeat(64),
                "Synthetic balanced journal",
                List.of(
                        new GlPostingEntry(
                                "001",
                                "Synthetic entry",
                                List.of(
                                        new GlPostingLine(
                                                "001",
                                                ACCOUNT1.toString(),
                                                1L,
                                                CURRENCY.toString(),
                                                1L,
                                                REF,
                                                2,
                                                "DR",
                                                amount,
                                                "Synthetic debit"),
                                        new GlPostingLine(
                                                "002",
                                                ACCOUNT2.toString(),
                                                1L,
                                                CURRENCY.toString(),
                                                1L,
                                                REF,
                                                2,
                                                "CR",
                                                amount,
                                                "Synthetic credit")))));
    }

    static class Driver implements ConnectionFactory {
        int commits, rollbacks;
        boolean failCommit;
        long leaseMillis = 5000, lastNativeRecordedAt;
        java.util.function.LongSupplier epoch = System::currentTimeMillis;
        Runnable afterCommit = () -> {};
        String operation = "";

        static Object value(Object raw) {
            return raw instanceof io.r2dbc.spi.Parameter p ? p.getValue() : raw;
        }

        static String field(String sql, Map<Integer, Object> bindings, String key) {
            var matcher =
                    java.util.regex.Pattern.compile(
                                    "set_config\\('firefly\\.gl\\." + key + "',\\$(\\d+),true\\)")
                            .matcher(sql);
            if (!matcher.find())
                throw new IllegalStateException("Missing synthetic native field " + key);
            return value(bindings.get(Integer.parseInt(matcher.group(1)) - 1)).toString();
        }

        Statement statement(String sql) {
            var bindings = new HashMap<Integer, Object>();
            return (Statement)
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class[] {Statement.class},
                            (p, m, a) -> {
                                if (m.getName().equals("bind")) {
                                    bindings.put((Integer) a[0], a[1]);
                                    return p;
                                }
                                if (m.getName().equals("execute")) {
                                    var nativeValues = new HashMap<String, Object>();
                                    if (sql.startsWith("WITH gl_clock")) {
                                        long now = epoch.getAsLong();
                                        lastNativeRecordedAt = now;
                                        var marker =
                                                java.util.regex.Pattern.compile(
                                                                "LEAST\\(CAST\\(\\$(\\d+) AS"
                                                                        + " bigint\\)")
                                                        .matcher(sql);
                                        assertTrue(marker.find());
                                        long admitted =
                                                Long.parseLong(
                                                        value(
                                                                        bindings.get(
                                                                                Integer.parseInt(
                                                                                                marker
                                                                                                        .group(
                                                                                                                1))
                                                                                        - 1))
                                                                .toString());
                                        nativeValues.put("db_now_ms", now);
                                        nativeValues.put(
                                                "effective_until_ms",
                                                Math.min(admitted, now + leaseMillis));
                                        nativeValues.put("transaction_id", "12345");
                                        for (var pair :
                                                Map.ofEntries(
                                                                Map.entry("tenant", "tenant_id"),
                                                                Map.entry("book", "book_id"),
                                                                Map.entry("operation", "operation"),
                                                                Map.entry("action", "action_id"),
                                                                Map.entry("posting", "posting_id"),
                                                                Map.entry(
                                                                        "intent", "intent_digest"),
                                                                Map.entry(
                                                                        "accounts", "account_ids"),
                                                                Map.entry(
                                                                        "actor_issuer",
                                                                        "actor_issuer"),
                                                                Map.entry(
                                                                        "actor_subject",
                                                                        "actor_subject"))
                                                        .entrySet())
                                            nativeValues.put(
                                                    pair.getValue(),
                                                    field(sql, bindings, pair.getKey()));
                                        operation = nativeValues.get("operation").toString();
                                    }
                                    return Mono.just(
                                            new Result() {
                                                public Publisher<Long> getRowsUpdated() {
                                                    return Mono.just(1L);
                                                }

                                                public <T> Publisher<T> map(
                                                        BiFunction<Row, RowMetadata, ? extends T>
                                                                f) {
                                                    Row row =
                                                            (Row)
                                                                    Proxy.newProxyInstance(
                                                                            getClass()
                                                                                    .getClassLoader(),
                                                                            new Class[] {Row.class},
                                                                            (rp, rm, ra) ->
                                                                                    sql.startsWith(
                                                                                                    "WITH protected")
                                                                                            ? Boolean
                                                                                                    .TRUE
                                                                                            : nativeValues
                                                                                                    .get(
                                                                                                            (String)
                                                                                                                    ra[
                                                                                                                            0]));
                                                    return Mono.just(f.apply(row, null));
                                                }

                                                public <T> Publisher<T> map(
                                                        Function<
                                                                        ? super
                                                                                io.r2dbc.spi
                                                                                        .Readable,
                                                                        ? extends T>
                                                                f) {
                                                    return Flux.empty();
                                                }

                                                public Result filter(Predicate<Segment> f) {
                                                    return this;
                                                }

                                                public <T> Publisher<T> flatMap(
                                                        Function<
                                                                        Segment,
                                                                        ? extends
                                                                                Publisher<
                                                                                        ? extends
                                                                                                T>>
                                                                f) {
                                                    return Flux.empty();
                                                }
                                            });
                                }
                                if (m.getReturnType() == Statement.class) return p;
                                throw new UnsupportedOperationException(m.getName());
                            });
        }

        Connection c =
                (Connection)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {Connection.class},
                                (p, m, a) -> {
                                    switch (m.getName()) {
                                        case "createStatement":
                                            return statement((String) a[0]);
                                        case "isAutoCommit":
                                            return true;
                                        case "commitTransaction":
                                            commits++;
                                            if (!failCommit) afterCommit.run();
                                            return failCommit
                                                    ? Mono.error(
                                                            new IllegalStateException(
                                                                    "synthetic commit failure"))
                                                    : Mono.empty();
                                        case "rollbackTransaction":
                                            rollbacks++;
                                            return Mono.empty();
                                        case "beginTransaction",
                                        "close",
                                        "setAutoCommit",
                                        "setTransactionIsolationLevel":
                                            return Mono.empty();
                                        default:
                                            throw new UnsupportedOperationException(m.getName());
                                    }
                                });

        public Publisher<? extends Connection> create() {
            return Mono.just(c);
        }

        public ConnectionFactoryMetadata getMetadata() {
            return () -> "PostgreSQL";
        }
    }

    static class Fixture {
        final Driver driver = new Driver();
        final List<String> writes = new ArrayList<>();
        final List<Object> rows = new ArrayList<>();
        GlNativeJournalAction action;
        GlNativePosting posting;
        long sequence;
        int sourceCalls, approvals;
        boolean enabled = true,
                sourcePresent = true,
                deny,
                expiredSource,
                expiredCurrency,
                wrongSourceActor,
                failEvent,
                failFinancialCommit,
                invalidSourceDeadline,
                staleCurrency,
                duplicateSource;
        long observedCurrencyAt;
        boolean controlledClock, policyNever;
        final java.util.concurrent.atomic.AtomicInteger policyCancelled =
                new java.util.concurrent.atomic.AtomicInteger();
        long nativeLeaseMillis = 5000;
        final java.util.concurrent.atomic.AtomicLong nanos =
                new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        final java.util.concurrent.atomic.AtomicLong epoch =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        Runnable afterFinancialCommit = () -> {}, afterReadCommit = () -> {}, onPolicy = () -> {};
        final Instant storedRecordedAt = Instant.parse("2026-10-26T12:34:56.123Z");
        final GlPostingRequest request =
                request("100", Instant.now().plusSeconds(40).toEpochMilli());
        SecurityPrincipal actor =
                SecurityPrincipal.builder()
                        .subject("synthetic-accountant")
                        .issuer("https://synthetic-accounting.invalid")
                        .claims(Map.of("exp", Instant.now().plusSeconds(120)))
                        .build();
        final GlNativePostingRepository repository =
                (GlNativePostingRepository)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {GlNativePostingRepository.class},
                                (p, m, a) ->
                                        Mono.defer(
                                                () -> {
                                                    switch (m.getName()) {
                                                        case "findAction":
                                                            return Mono.justOrEmpty(action);
                                                        case "findPosting":
                                                            return Mono.justOrEmpty(posting);
                                                        case "findBook":
                                                            return Mono.just(
                                                                    new GlNativeBook(
                                                                            BOOK, TENANT, "ACTIVE",
                                                                            1));
                                                        case "findPeriod":
                                                            return Mono.just(
                                                                    new GlNativePeriod(
                                                                            PERIOD,
                                                                            TENANT,
                                                                            BOOK,
                                                                            LocalDate.of(
                                                                                    2026, 1, 1),
                                                                            LocalDate.of(
                                                                                    2027, 1, 1),
                                                                            "OPEN",
                                                                            1));
                                                        case "findMapping":
                                                            return Mono.just(
                                                                    new GlNativeMapping(
                                                                            MAPPING,
                                                                            TENANT,
                                                                            BOOK,
                                                                            1,
                                                                            "c".repeat(64)));
                                                        case "findAccount":
                                                            return Mono.just(
                                                                    new GlNativeAccount(
                                                                            (UUID) a[2],
                                                                            TENANT,
                                                                            BOOK,
                                                                            "POSTING",
                                                                            CURRENCY,
                                                                            "ACTIVE",
                                                                            1));
                                                        case "findCurrency":
                                                            return Mono.just(
                                                                    new GlNativeCurrencyAdmission(
                                                                            CURRENCY,
                                                                            TENANT,
                                                                            BOOK,
                                                                            POLICY,
                                                                            CURRENCY_ID,
                                                                            "USD",
                                                                            1,
                                                                            REF.digest(),
                                                                            REF.encodingVersion(),
                                                                            POLICY_BODY,
                                                                            2,
                                                                            "ACTIVE",
                                                                            1));
                                                        case "findSequence":
                                                            return Mono.just(
                                                                    new GlNativeBookSequence(
                                                                            TENANT, BOOK,
                                                                            sequence));
                                                        case "advanceSequence":
                                                            writes.add("sequence");
                                                            sequence++;
                                                            return Mono.just(sequence);
                                                        case "appendAction":
                                                            if (duplicateSource)
                                                                return Mono.error(
                                                                        new org.springframework.dao
                                                                                .DuplicateKeyException(
                                                                                "synthetic source"
                                                                                    + " already"
                                                                                    + " claimed by"
                                                                                    + " another"
                                                                                    + " action"));
                                                            writes.add("action");
                                                            rows.add(a[0]);
                                                            var inserted =
                                                                    (GlNativeJournalAction) a[0];
                                                            action =
                                                                    new GlNativeJournalAction(
                                                                            inserted.actionId(),
                                                                            inserted.tenantId(),
                                                                            inserted.bookId(),
                                                                            inserted.kind(),
                                                                            inserted.intentDigest(),
                                                                            inserted
                                                                                    .canonicalIntent(),
                                                                            inserted
                                                                                    .economicDigest(),
                                                                            inserted
                                                                                    .canonicalEconomicIntent(),
                                                                            inserted
                                                                                    .sourceClaimKey(),
                                                                            inserted.accountIds(),
                                                                            inserted
                                                                                    .notAfterEpochMillis(),
                                                                            inserted.receiptId(),
                                                                            inserted.actorIssuer(),
                                                                            inserted.actorSubject(),
                                                                            inserted
                                                                                    .authorityUntilEpochMillis(),
                                                                            inserted.postingId(),
                                                                            inserted.requestShape(),
                                                                            inserted
                                                                                    .originalRequest(),
                                                                            storedRecordedAt);
                                                            return Mono.just(a[0]);
                                                        case "appendPosting":
                                                            writes.add("posting");
                                                            rows.add(a[0]);
                                                            posting = (GlNativePosting) a[0];
                                                            return Mono.just(a[0]);
                                                        case "appendEntry":
                                                            writes.add("entry");
                                                            rows.add(a[0]);
                                                            return Mono.just(a[0]);
                                                        case "appendLine":
                                                            writes.add("line");
                                                            rows.add(a[0]);
                                                            return Mono.just(a[0]);
                                                        case "appendEvent":
                                                            var event = (GlNativeJournalEvent) a[0];
                                                            if (failEvent
                                                                    && event.id()
                                                                            .channel()
                                                                            .equals("OUTBOX"))
                                                                return Mono.error(
                                                                        new IllegalStateException(
                                                                                "synthetic outbox"
                                                                                    + " persistence"
                                                                                    + " failure"));
                                                            if (failFinancialCommit
                                                                    && event.id()
                                                                            .channel()
                                                                            .equals("OUTBOX"))
                                                                driver.failCommit = true;
                                                            writes.add(event.id().channel());
                                                            rows.add(a[0]);
                                                            return Mono.just(a[0]);
                                                        default:
                                                            return Mono.error(
                                                                    new UnsupportedOperationException(
                                                                            m.getName()));
                                                    }
                                                }));

        Object service() {
            var factory = new StaticListableBeanFactory();
            var env =
                    new MockEnvironment()
                            .withProperty(
                                    "firefly.accounting.native-posting.enabled",
                                    Boolean.toString(enabled))
                            .withProperty("firefly.security.resource-server.strict-policy", "true")
                            .withProperty(
                                    "firefly.accounting.approval.trusted-issuers",
                                    "https://synthetic-approval.invalid");
            factory.addBean("contexts", (SecurityContextPort) () -> Mono.just(actor));
            factory.addBean(
                    "policy",
                    (PolicyDecisionPort)
                            (p, a, r, c) -> {
                                onPolicy.run();
                                if (policyNever)
                                    return Mono.<Decision>never()
                                            .doOnCancel(policyCancelled::incrementAndGet);
                                return Mono.just(
                                        deny
                                                ? Decision.deny("synthetic withdrawn")
                                                : Decision.permit());
                            });
            factory.addBean(
                    "approval",
                    (AccountingApprovalPort)
                            (p, i) -> {
                                approvals++;
                                return Mono.just(
                                        new AccountingApproval(
                                                i.tenantId(),
                                                i.bookId(),
                                                i.operation(),
                                                i.intentDigest(),
                                                p.issuer(),
                                                p.subject(),
                                                "https://synthetic-approval.invalid",
                                                "synthetic-exact-approval",
                                                "d".repeat(64),
                                                Instant.now().plusSeconds(60).toEpochMilli()));
                            });
            factory.addBean("repository", repository);
            driver.epoch = controlledClock ? epoch::get : System::currentTimeMillis;
            driver.leaseMillis = nativeLeaseMillis;
            driver.afterCommit =
                    () -> {
                        if (driver.operation.equals("post")) afterFinancialCommit.run();
                        else if (driver.operation.equals("read")) afterReadCommit.run();
                    };
            var configuration = new GlNativePostingConfiguration();
            var nativeTemplate = configuration.nativeTemplate(
                    new org.springframework.data.r2dbc.core.R2dbcEntityTemplate(
                            DatabaseClient.create(driver),
                            org.springframework.data.r2dbc.dialect.PostgresDialect.INSTANCE));
            GlAccountingTransaction transaction =
                    controlledClock
                            ? new GlAccountingTransaction(
                                    new org.springframework.r2dbc.connection.R2dbcTransactionManager(
                                            nativeTemplate.getDatabaseClient().getConnectionFactory()),
                                    new GlAccountingDatabaseContext(nativeTemplate.getDatabaseClient()),
                                    nativeTemplate,
                                    nanos::get,
                                    epoch::get)
                            : configuration.transaction(nativeTemplate);
            factory.addBean("transaction", transaction);
            factory.addBean(
                    "currency",
                    (AccountingCurrencyObservationPort)
                            r -> {
                                observedCurrencyAt =
                                        Instant.now()
                                                .minusSeconds(staleCurrency ? 6 : 0)
                                                .toEpochMilli();
                                return Mono.just(
                                        new GlObservedCurrency(
                                                r,
                                                POLICY_BODY,
                                                r.encodingVersion(),
                                                r.digest(),
                                                2,
                                                Instant.now().minusSeconds(60).toEpochMilli(),
                                                Instant.now()
                                                        .plusSeconds(expiredCurrency ? -1 : 30)
                                                        .toEpochMilli(),
                                                "synthetic-publication",
                                                id(20).toString(),
                                                Instant.now().minusSeconds(60).toEpochMilli(),
                                                observedCurrencyAt));
                            });
            Class<?> source =
                    assertDoesNotThrow(
                            () ->
                                    Class.forName(
                                            "io.fireflysoftware.core.accounting.core.services.posting.AccountingPostingSourcePort"));
            if (sourcePresent)
                factory.addBean(
                        "source",
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {source},
                                (p, m, a) -> {
                                    sourceCalls++;
                                    var v = (GlPostingRules.Validated) a[1];
                                    return Mono.just(
                                            assertDoesNotThrow(
                                                    () ->
                                                            Class.forName(
                                                                            "io.fireflysoftware.core.accounting.core.services.posting.GlPostingSourceObservation")
                                                                    .getConstructor(
                                                                            String.class,
                                                                            String.class,
                                                                            String.class,
                                                                            String.class,
                                                                            String.class,
                                                                            String.class,
                                                                            String.class,
                                                                            long.class)
                                                                    .newInstance(
                                                                            TENANT.toString(),
                                                                            BOOK.toString(),
                                                                            v.request().actionId(),
                                                                            v.intentDigest(),
                                                                            v.sourceClaimKey(),
                                                                            actor.issuer(),
                                                                            wrongSourceActor
                                                                                    ? "other-actor"
                                                                                    : actor
                                                                                            .subject(),
                                                                            invalidSourceDeadline
                                                                                    ? Long.MAX_VALUE
                                                                                    : Instant.now()
                                                                                            .plusSeconds(
                                                                                                    expiredSource
                                                                                                            ? -1
                                                                                                            : 20)
                                                                                            .toEpochMilli())));
                                }));
            var authority =
                    new GlAccountingAuthority(
                            factory.getBeanProvider(SecurityContextPort.class),
                            factory.getBeanProvider(PolicyDecisionPort.class),
                            factory.getBeanProvider(AccountingApprovalPort.class),
                            factory.getBeanProvider(AccountingBookOwnerPort.class),
                            env);
            return assertDoesNotThrow(
                    () ->
                            Class.forName(
                                            "io.fireflysoftware.core.accounting.core.services.posting.impl.GlPostingApplicationServiceImpl")
                                    .getConstructors()[0]
                                    .newInstance(
                                            authority,
                                            factory.getBeanProvider(
                                                    GlNativePostingRepository.class),
                                            factory.getBeanProvider(GlAccountingTransaction.class),
                                            factory.getBeanProvider(
                                                    AccountingCurrencyObservationPort.class),
                                            factory.getBeanProvider(source),
                                            env));
        }

        Object invoke(Object service, String operation, GlPostingRequest r) {
            return assertDoesNotThrow(
                            () ->
                                    (Mono<?>)
                                            service.getClass()
                                                    .getMethod(
                                                            operation,
                                                            String.class,
                                                            String.class,
                                                            byte[].class)
                                                    .invoke(
                                                            service,
                                                            TENANT.toString(),
                                                            BOOK.toString(),
                                                            GlPostingIntentCodec.encode(
                                                                            TENANT.toString(),
                                                                            BOOK.toString(),
                                                                            r)
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)))
                    .block();
        }

        Object post() {
            return invoke(service(), "post", request);
        }
    }

    static Object value(Object record, String field) {
        return assertDoesNotThrow(() -> record.getClass().getMethod(field).invoke(record));
    }

    static void status(int expected, Runnable action) {
        var e = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(expected, e.getStatusCode().value());
    }

    @Test
    void requestBytesAreFrozenBeforeReactiveSubscription() {
        var f = new Fixture();
        var service = f.service();
        var bytes =
                GlPostingIntentCodec.encode(TENANT.toString(), BOOK.toString(), f.request)
                        .getBytes(StandardCharsets.UTF_8);
        var pending =
                assertDoesNotThrow(
                        () ->
                                (Mono<?>)
                                        service.getClass()
                                                .getMethod(
                                                        "post",
                                                        String.class,
                                                        String.class,
                                                        byte[].class)
                                                .invoke(
                                                        service,
                                                        TENANT.toString(),
                                                        BOOK.toString(),
                                                        bytes));
        Arrays.fill(bytes, (byte) ' ');
        assertNotNull(pending.block());
        assertEquals(7, f.rows.size());
    }

    @Test
    void sourceDeadlineMustFitTheDeclaredUtcRange() {
        var f = new Fixture();
        f.invalidSourceDeadline = true;
        status(403, f::post);
        assertTrue(f.writes.isEmpty());
    }

    @Test
    void staleCurrencyObservationCannotStartFinancialWrites() {
        var f = new Fixture();
        f.staleCurrency = true;
        status(409, f::post);
        assertTrue(f.writes.isEmpty());
    }

    @Test
    void mutationDeadlineIsBoundToActualCurrencyObservationAge() {
        var f = new Fixture();
        f.post();
        assertTrue(f.action.authorityUntilEpochMillis() <= f.observedCurrencyAt + 5000);
        assertTrue(f.action.authorityUntilEpochMillis() > f.observedCurrencyAt);
    }

    @Test
    void duplicateSourceWithoutExactRetainedActionIsConflict() {
        var f = new Fixture();
        f.duplicateSource = true;
        status(409, f::post);
        assertTrue(f.rows.isEmpty());
        assertTrue(f.driver.rollbacks > 0);
    }

    @Test
    void missingRetainedPostingCannotTurnIntoNewFinancialWork() {
        var f = new Fixture();
        f.post();
        f.posting = null;
        status(503, f::post);
        assertEquals(7, f.rows.size());
    }

    @Test
    void missingScopedReceiptDoesNotClaimNoEffectOrInsert() {
        var f = new Fixture();
        status(404, () -> f.invoke(f.service(), "recover", f.request));
        assertTrue(f.rows.isEmpty());
        assertEquals(0, f.sourceCalls);
    }

    @Test
    void eventPayloadsBindActualActionReceiptAndIntent() {
        var f = new Fixture();
        f.post();
        for (var row : f.rows) {
            if (row instanceof GlNativeJournalEvent event) {
                var document =
                        assertDoesNotThrow(
                                () ->
                                        com.fasterxml.jackson.databind.json.JsonMapper.builder()
                                                .build()
                                                .readTree(event.payload()));
                assertEquals(f.action.actionId().toString(), document.get("actionId").asText());
                assertEquals(f.action.receiptId().toString(), document.get("receiptId").asText());
                assertEquals(f.action.intentDigest(), document.get("intentDigest").asText());
                assertEquals(GlPostingRules.digest(event.payload()), event.payloadDigest());
                assertEquals("GL_ACCOUNTING_EVENT_V1", event.payloadEncoding());
            }
        }
    }

    @Test
    void foundationShapeCannotBeRelabeledAsQualifiedPostingRecovery() {
        var f = new Fixture();
        f.post();
        var a = f.action;
        f.action =
                new GlNativeJournalAction(
                        a.actionId(),
                        a.tenantId(),
                        a.bookId(),
                        a.kind(),
                        a.intentDigest(),
                        a.canonicalIntent(),
                        a.economicDigest(),
                        a.canonicalEconomicIntent(),
                        a.sourceClaimKey(),
                        a.accountIds(),
                        a.notAfterEpochMillis(),
                        a.receiptId(),
                        a.actorIssuer(),
                        a.actorSubject(),
                        a.authorityUntilEpochMillis(),
                        a.postingId(),
                        "GL_FOUNDATION_V1",
                        null);
        status(503, () -> f.invoke(f.service(), "recover", f.request));
    }

    @Test
    void retainedPostingMustBindTheOriginalRequestHeader() {
        var f = new Fixture();
        f.post();
        var p = f.posting;
        f.posting =
                new GlNativePosting(
                        p.id(),
                        p.tenantId(),
                        p.bookId(),
                        p.actionId(),
                        p.kind(),
                        p.journalSequence(),
                        p.expectedBookVersion(),
                        p.periodId(),
                        p.expectedPeriodVersion(),
                        p.businessDate(),
                        id(25),
                        p.mappingVersion(),
                        p.mappingDigest(),
                        null);
        status(503, () -> f.invoke(f.service(), "recover", f.request));
    }

    @Test
    void currentPolicyWorkCannotMintAReplacementNativeWindow() {
        var f = new Fixture();
        f.controlledClock = true;
        f.afterFinancialCommit = () -> f.onPolicy = () -> f.nanos.addAndGet(6000000000L);
        var error = assertThrows(RuntimeException.class, f::post);
        assertEquals("GlPostingCommitKnownException", error.getClass().getSimpleName());
        assertNull(error.getCause());
        assertEquals(7, f.rows.size());
    }

    @Test
    void confirmedCommitWithExpiredDeliveryKeepsOnlySafeCommitFact() {
        var f = new Fixture();
        f.controlledClock = true;
        f.nativeLeaseMillis = 5000;
        f.afterFinancialCommit = () -> f.nanos.addAndGet(6000000000L);
        var error = assertThrows(RuntimeException.class, f::post);
        assertEquals("GlPostingCommitKnownException", error.getClass().getSimpleName());
        assertEquals(ACTION, value(error, "actionReference"));
        assertEquals(true, value(error, "commitKnown"));
        assertNull(error.getCause());
        assertFalse(error.toString().contains("synthetic-receipt"));
        assertEquals(7, f.rows.size());
    }

    @Test
    void withdrawnFinalPolicyCannotEraseKnownCommitOrExposeReceipt() {
        var f = new Fixture();
        f.afterFinancialCommit = () -> f.deny = true;
        var error = assertThrows(RuntimeException.class, f::post);
        assertEquals("GlPostingCommitKnownException", error.getClass().getSimpleName());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(f.action.receiptId().toString()));
        assertEquals(7, f.rows.size());
    }

    @Test
    void changedFinalActorCannotReceiveTheCommittedReceipt() {
        var f = new Fixture();
        f.afterFinancialCommit =
                () ->
                        f.actor =
                                SecurityPrincipal.builder()
                                        .subject("other-current-reader")
                                        .issuer(f.actor.issuer())
                                        .claims(Map.of("exp", Instant.now().plusSeconds(120)))
                                        .build();
        var error = assertThrows(RuntimeException.class, f::post);
        assertEquals("GlPostingCommitKnownException", error.getClass().getSimpleName());
        assertEquals(7, f.rows.size());
    }

    @Test
    void protectedRecoveryCannotRenewExpiredNativeReadWindow() {
        var f = new Fixture();
        f.post();
        f.controlledClock = true;
        f.nativeLeaseMillis = 5000;
        f.afterReadCommit = () -> f.nanos.addAndGet(6000000000L);
        var error =
                assertThrows(
                        RuntimeException.class, () -> f.invoke(f.service(), "recover", f.request));
        assertNotEquals("GlPostingCommitKnownException", error.getClass().getSimpleName());
        assertFalse(error.toString().contains(f.action.receiptId().toString()));
        assertEquals(7, f.rows.size());
    }

    @Test
    void actualStoredActionTimeIsNotReplacedByContextInstallationTime() {
        var f = new Fixture();
        var receipt = f.post();
        assertEquals(f.storedRecordedAt, value(receipt, "recordedAt"));
        assertNotEquals(
                Instant.ofEpochMilli(f.driver.lastNativeRecordedAt), value(receipt, "recordedAt"));
    }

    @Test
    void uncertainCommitNeverBecomesKnownOrAutomaticallyReposts() {
        var f = new Fixture();
        f.failFinancialCommit = true;
        var error = assertThrows(RuntimeException.class, f::post);
        assertEquals("GlPostingOutcomeUncertainException", error.getClass().getSimpleName());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(f.action.receiptId().toString()));
        assertEquals(7, f.rows.size());
        assertEquals(1, f.sequence);
    }

    @Test
    void defaultOffRejectsBeforeSourceOrDatabaseEffects() {
        var f = new Fixture();
        f.enabled = false;
        status(503, f::post);
        assertTrue(f.writes.isEmpty());
        assertEquals(0, f.sourceCalls);
    }

    @Test
    void missingGenuineSourceAdmissionIsUnavailable() {
        var f = new Fixture();
        f.sourcePresent = false;
        status(503, f::post);
        assertTrue(f.writes.isEmpty());
    }

    @Test
    void normalSyntheticPostUsesExactSevenRowsAndOneSequenceCas() {
        var f = new Fixture();
        var result = f.post();
        assertEquals(
                List.of(
                        "action",
                        "sequence",
                        "posting",
                        "entry",
                        "line",
                        "line",
                        "AUDIT",
                        "OUTBOX"),
                f.writes);
        assertEquals(7, f.rows.size());
        assertEquals(1L, value(result, "journalSequence"));
        assertEquals(false, value(result, "recovered"));
        assertEquals(
                GlPostingIntentCodec.encode(TENANT.toString(), BOOK.toString(), f.request),
                f.action.originalRequest());
        assertEquals("GL_POSTING_REQUEST_V1", f.action.requestShape());
    }

    @Test
    void protectedExactReplayDoesNotRenewMutationApprovalOrAppend() {
        var f = new Fixture();
        var first = f.post();
        int approvals = f.approvals, calls = f.sourceCalls;
        var next = f.post();
        assertEquals(value(first, "receiptId"), value(next, "receiptId"));
        assertEquals(true, value(next, "recovered"));
        assertEquals(approvals, f.approvals);
        assertEquals(calls, f.sourceCalls);
        assertEquals(7, f.rows.size());
    }

    @Test
    void protectedRecoveryRetainsReceiptAfterMutationDependenciesDisappear() {
        var f = new Fixture();
        var first = f.post();
        f.sourcePresent = false;
        f.expiredCurrency = true;
        var recovered = f.invoke(f.service(), "recover", f.request);
        assertEquals(value(first, "receiptId"), value(recovered, "receiptId"));
        assertEquals(7, f.rows.size());
    }

    @Test
    void changedIntentCannotRecoverAnExistingAction() {
        var f = new Fixture();
        f.post();
        var changed =
                new GlPostingRequest(
                        f.request.actionId(),
                        f.request.periodId(),
                        1L,
                        1L,
                        f.request.notAfterEpochMillis(),
                        f.request.businessDate(),
                        f.request.sourceOccurrenceEpochMillis(),
                        f.request.source(),
                        f.request.mappingId(),
                        1L,
                        f.request.mappingDigest(),
                        "Changed description",
                        f.request.entries());
        status(409, () -> f.invoke(f.service(), "recover", changed));
        assertEquals(7, f.rows.size());
    }

    @Test
    void withdrawnCurrentPolicyCannotReadRetainedReceipt() {
        var f = new Fixture();
        f.post();
        f.deny = true;
        status(403, () -> f.invoke(f.service(), "recover", f.request));
        assertEquals(7, f.rows.size());
    }

    @Test
    void expiredSourceObservationNeverStartsFinancialWrites() {
        var f = new Fixture();
        f.expiredSource = true;
        status(403, f::post);
        assertTrue(f.writes.isEmpty());
    }

    @Test
    void sourceObservationCannotChangeOriginalActor() {
        var f = new Fixture();
        f.wrongSourceActor = true;
        status(403, f::post);
        assertTrue(f.writes.isEmpty());
    }

    @Test
    void expiredCurrencyObservationNeverStartsFinancialWrites() {
        var f = new Fixture();
        f.expiredCurrency = true;
        status(409, f::post);
        assertTrue(f.writes.isEmpty());
    }

    @Test
    void mutationRepositoryFailurePropagatesThroughRealTransactionRollback() {
        var f = new Fixture();
        f.failEvent = true;
        assertThrows(IllegalStateException.class, f::post);
        assertTrue(f.driver.rollbacks > 0);
        assertFalse(f.writes.contains("OUTBOX"));
    }

    @Test
    void nativeCommitFailureCannotReturnSuccessfulReceipt() {
        var f = new Fixture();
        f.failFinancialCommit = true;
        assertThrows(RuntimeException.class, f::post);
        assertEquals(7, f.rows.size());
        assertTrue(f.driver.commits >= 2);
    }
}
