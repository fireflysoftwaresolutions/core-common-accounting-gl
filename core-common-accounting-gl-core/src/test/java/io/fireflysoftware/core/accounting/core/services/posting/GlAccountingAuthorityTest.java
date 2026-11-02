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

import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class GlAccountingAuthorityTest {
    static final String TENANT = UUID.randomUUID().toString(),
            BOOK = UUID.randomUUID().toString(),
            DIGEST = GlPostingRules.digest("GL_POSTING_INTENT_V1\nsynthetic-exact-tuple\n");

    static SecurityPrincipal actor(Object expiry) {
        return SecurityPrincipal.builder()
                .subject("synthetic-accountant")
                .issuer("https://synthetic-accounting.invalid")
                .claims(expiry == null ? Map.of() : Map.of("exp", expiry))
                .build();
    }

    static AccountingApprovalIntent intent(String operation) {
        var canonical =
                (operation.equals("book.enroll")
                                ? "GL_BOOK_ENROLLMENT_V1"
                                : operation.equals("read")
                                        ? "GL_READ_CONTEXT_V1"
                                        : "GL_POSTING_INTENT_V1")
                        + "\nsynthetic-exact-tuple\n";
        return new AccountingApprovalIntent(
                TENANT,
                BOOK,
                operation,
                GlPostingRules.digest(canonical),
                canonical,
                Map.of("legalEntityReference", "synthetic-bank-entity"));
    }

    static AccountingApproval proof(SecurityPrincipal p, AccountingApprovalIntent i) {
        return new AccountingApproval(
                i.tenantId(),
                i.bookId(),
                i.operation(),
                i.intentDigest(),
                p.issuer(),
                p.subject(),
                "https://synthetic-approval.invalid",
                "synthetic-exact-approval",
                "b".repeat(64),
                Instant.now().plusSeconds(45).toEpochMilli());
    }

    static GlAccountingAuthority authority(
            SecurityPrincipal actor,
            PolicyDecisionPort pdp,
            AccountingApprovalPort approvals,
            AccountingBookOwnerPort book) {
        return authority(actor, pdp, approvals, book, "true");
    }

    static GlAccountingAuthority authority(
            SecurityPrincipal actor,
            PolicyDecisionPort pdp,
            AccountingApprovalPort approvals,
            AccountingBookOwnerPort book,
            String strict) {
        var f = new StaticListableBeanFactory();
        if (actor != null) f.addBean("actor", (SecurityContextPort) () -> Mono.just(actor));
        if (pdp != null) f.addBean("policy", pdp);
        if (approvals != null) f.addBean("approval", approvals);
        if (book != null) f.addBean("bookOwner", book);
        var env =
                new MockEnvironment()
                        .withProperty(
                                "firefly.accounting.approval.trusted-issuers",
                                "https://synthetic-approval.invalid");
        if (strict != null)
            env.withProperty("firefly.security.resource-server.strict-policy", strict);
        return new GlAccountingAuthority(
                f.getBeanProvider(SecurityContextPort.class),
                f.getBeanProvider(PolicyDecisionPort.class),
                f.getBeanProvider(AccountingApprovalPort.class),
                f.getBeanProvider(AccountingBookOwnerPort.class),
                env);
    }

    static void denied(GlAccountingAuthority a, String operation, boolean approval) {
        assertThatThrownBy(() -> a.authorize(intent(operation), approval).block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void databaseAdmissionRetainsExactIntentAndOnlyActuallyAuthorizedAccountResources() {
        var principal = actor(Instant.now().plusSeconds(60));
        var authority =
                authority(
                        principal,
                        (a, b, c, d) -> Mono.just(Decision.permit()),
                        (p, i) -> Mono.just(proof(p, i)),
                        null);
        var original = intent("post");
        var accounts =
                new ArrayList<>(
                        List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        var expected = Set.copyOf(accounts);
        var admission = authority.authorizeAccounts(original, accounts, true).block();
        accounts.clear();
        assertThat(admission.intent()).isEqualTo(original);
        assertThat(admission.accountIds()).isEqualTo(expected);
        assertThatThrownBy(() -> admission.accountIds().add(UUID.randomUUID().toString()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(authority.authorize(original, true).block().accountIds()).isEmpty();
    }

    @Test
    void resourceDiscoveryRequiresItsExplicitCurrentActionAndExactPostingIdentity() {
        var posting = UUID.randomUUID().toString();
        var canonical =
                GlPostingRules.canonical(
                        "GL_READ_CONTEXT_V1", List.of(TENANT, BOOK, "read.resources", posting));
        var intent =
                new AccountingApprovalIntent(
                        TENANT,
                        BOOK,
                        "read.resources",
                        GlPostingRules.digest(canonical),
                        canonical,
                        Map.of("postingId", posting));
        var principal = actor(Instant.now().plusSeconds(60));
        var allowed =
                authority(
                        principal,
                        (a, action, resource, values) ->
                                Mono.just(
                                        action.equals("accounting.gl.read.resources")
                                                        && resource.equals(
                                                                "accounting/book/" + BOOK)
                                                        && posting.equals(values.get("postingId"))
                                                ? Decision.permit()
                                                : Decision.deny(
                                                        "Synthetic exact discovery tuple denied")),
                        null,
                        null);
        assertThat(allowed.authorize(intent, false).block().intent()).isEqualTo(intent);
        var generic =
                authority(
                        principal,
                        (a, action, r, c) ->
                                Mono.just(
                                        action.equals("accounting.gl.read")
                                                ? Decision.permit()
                                                : Decision.deny(
                                                        "Synthetic discovery action denied")),
                        null,
                        null);
        assertThatThrownBy(() -> generic.authorize(intent, false).block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        var missing =
                new AccountingApprovalIntent(
                        TENANT, BOOK, "read.resources", intent.intentDigest(), canonical, Map.of());
        assertThatThrownBy(() -> allowed.authorize(missing, false).block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void absentCurrentAuthorityOrExplicitStrictModeNeverPermits() {
        var p = actor(Instant.now().plusSeconds(60));
        denied(authority(null, null, null, null), "read", false);
        denied(authority(p, null, null, null), "read", false);
        for (String flag : new String[] {null, "false"}) {
            var calls = new AtomicInteger();
            denied(
                    authority(
                            p,
                            (a, b, c, d) -> {
                                calls.incrementAndGet();
                                return Mono.just(Decision.permit());
                            },
                            null,
                            null,
                            flag),
                    "read",
                    false);
            assertThat(calls).hasValue(0);
        }
        for (Object expiry :
                new Object[] {null, 1L, "tomorrow", Instant.MAX, Instant.now().minusSeconds(1)})
            denied(
                    authority(
                            actor(expiry),
                            (a, b, c, d) -> Mono.just(Decision.permit()),
                            null,
                            null),
                    "read",
                    false);
    }

    @Test
    void scopeIsExactAndEveryReplayObservesCurrentPolicy() {
        var calls = new AtomicInteger();
        var a =
                authority(
                        actor(Instant.now().plusSeconds(60)),
                        (p, action, resource, context) -> {
                            assertThat(action).isEqualTo("accounting.gl.read");
                            assertThat(resource).isEqualTo("accounting/book/" + BOOK);
                            assertThat(context)
                                    .containsEntry("tenantId", TENANT)
                                    .containsEntry("bookId", BOOK)
                                    .containsEntry("intentDigest", intent("read").intentDigest())
                                    .containsEntry("legalEntityReference", "synthetic-bank-entity");
                            return Mono.just(
                                    calls.getAndIncrement() == 0
                                            ? Decision.permit()
                                            : Decision.deny("withdrawn"));
                        },
                        null,
                        null);
        assertThat(a.authorize(intent("read"), false).block()).isNotNull();
        denied(a, "read", false);
        assertThat(calls).hasValue(2);
    }

    @Test
    void policyAbsenceErrorsObligationsAndMalformedEffectsDeny() {
        var p = actor(Instant.now().plusSeconds(60));
        for (PolicyDecisionPort pdp :
                List.<PolicyDecisionPort>of(
                        (a, b, c, d) -> Mono.empty(),
                        (a, b, c, d) -> Mono.error(new IllegalStateException("private")),
                        (a, b, c, d) -> Mono.just(Decision.indeterminate("unknown")),
                        (a, b, c, d) -> Mono.just(new Decision(null, List.of(), "malformed")),
                        (a, b, c, d) ->
                                Mono.just(
                                        Decision.permit(
                                                List.of(
                                                        Obligation.of(
                                                                "manual-review", Map.of()))))))
            denied(authority(p, pdp, null, null), "read", false);
    }

    @Test
    void financialApprovalIsDefaultDeniedAndBoundToTheWholeActorAndIntent() {
        var p = actor(Instant.now().plusSeconds(60));
        PolicyDecisionPort permit = (a, b, c, d) -> Mono.just(Decision.permit());
        denied(authority(p, permit, null, null), "post", true);
        denied(authority(p, permit, (actor, i) -> Mono.empty(), null), "post", true);
        denied(
                authority(
                        p,
                        permit,
                        (actor, i) -> Mono.error(new IllegalStateException("private")),
                        null),
                "post",
                true);
        var good = authority(p, permit, (actor, i) -> Mono.just(proof(actor, i)), null);
        var admitted = good.authorize(intent("post"), true).block();
        assertThat(admitted.approval().intentDigest()).isEqualTo(intent("post").intentDigest());
        var exact = proof(p, intent("post"));
        for (var bad :
                List.of(
                        new AccountingApproval(
                                UUID.randomUUID().toString(),
                                BOOK,
                                "post",
                                DIGEST,
                                p.issuer(),
                                p.subject(),
                                exact.approvalIssuer(),
                                exact.approvalReference(),
                                exact.approvalDigest(),
                                exact.validUntilEpochMillis()),
                        new AccountingApproval(
                                TENANT,
                                BOOK,
                                "post",
                                "c".repeat(64),
                                p.issuer(),
                                p.subject(),
                                exact.approvalIssuer(),
                                exact.approvalReference(),
                                exact.approvalDigest(),
                                exact.validUntilEpochMillis()),
                        new AccountingApproval(
                                TENANT,
                                BOOK,
                                "post",
                                DIGEST,
                                p.issuer(),
                                "different-actor",
                                exact.approvalIssuer(),
                                exact.approvalReference(),
                                exact.approvalDigest(),
                                exact.validUntilEpochMillis()),
                        new AccountingApproval(
                                TENANT,
                                BOOK,
                                "post",
                                DIGEST,
                                p.issuer(),
                                p.subject(),
                                "https://untrusted.invalid",
                                exact.approvalReference(),
                                exact.approvalDigest(),
                                exact.validUntilEpochMillis()),
                        new AccountingApproval(
                                TENANT,
                                BOOK,
                                "post",
                                DIGEST,
                                p.issuer(),
                                p.subject(),
                                exact.approvalIssuer(),
                                exact.approvalReference(),
                                exact.approvalDigest(),
                                Instant.now().minusSeconds(1).toEpochMilli())))
            denied(authority(p, permit, (actor, i) -> Mono.just(bad), null), "post", true);
    }

    @Test
    void genericApprovalCannotSubstituteForPrivilegedBookOwnership() {
        var p = actor(Instant.now().plusSeconds(60));
        PolicyDecisionPort permit = (a, b, c, d) -> Mono.just(Decision.permit());
        denied(
                authority(p, permit, (actor, i) -> Mono.just(proof(actor, i)), null),
                "book.enroll",
                true);
        var exact =
                authority(p, permit, null, (actor, i) -> Mono.just(proof(actor, i)))
                        .authorize(intent("book.enroll"), true)
                        .block();
        assertThat(exact.approval().operation()).isEqualTo("book.enroll");
    }

    @Test
    void stalledPolicyTimesOutAndAnActiveReactiveTransactionNeverCallsPolicy() {
        var p = actor(Instant.now().plusSeconds(60));
        reactor.test.StepVerifier.withVirtualTime(
                        () ->
                                authority(p, (a, b, c, d) -> Mono.never(), null, null)
                                        .authorize(intent("read"), false))
                .thenAwait(java.time.Duration.ofSeconds(6))
                .expectErrorSatisfies(
                        error ->
                                assertThat(error)
                                        .isInstanceOfSatisfying(
                                                ResponseStatusException.class,
                                                denied ->
                                                        assertThat(denied.getStatusCode().value())
                                                                .isEqualTo(403)))
                .verify();
        var calls = new AtomicInteger();
        var authority =
                authority(
                        p,
                        (a, b, c, d) -> {
                            calls.incrementAndGet();
                            return Mono.just(Decision.permit());
                        },
                        null,
                        null);
        var contextual =
                org.springframework.transaction.reactive.TransactionSynchronizationManager
                        .forCurrentTransaction()
                        .flatMap(
                                manager -> {
                                    manager.setActualTransactionActive(true);
                                    return authority.authorize(intent("read"), false);
                                })
                        .contextWrite(
                                org.springframework.transaction.reactive.TransactionContextManager
                                        .createTransactionContext());
        assertThatThrownBy(contextual::block)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        denied -> assertThat(denied.getStatusCode().value()).isEqualTo(409));
        assertThat(calls).hasValue(0);
    }

    @Test
    void financialApprovalCannotBeDisabledByAnInternalBooleanArgument() {
        var p = actor(Instant.now().plusSeconds(60));
        assertThatThrownBy(
                        () ->
                                authority(
                                                p,
                                                (a, b, c, d) -> Mono.just(Decision.permit()),
                                                null,
                                                null)
                                        .authorize(intent("post"), false)
                                        .block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        denied -> assertThat(denied.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void approvalCanonicalBytesMustMatchTheirExactVersionedDigestBeforeAnyPolicyCall() {
        var p = actor(Instant.now().plusSeconds(60));
        var calls = new AtomicInteger();
        var authority =
                authority(
                        p,
                        (a, b, c, d) -> {
                            calls.incrementAndGet();
                            return Mono.just(Decision.permit());
                        },
                        (actor, i) -> Mono.just(proof(actor, i)),
                        null);
        var original = intent("post");
        for (var bytes :
                List.of(
                        original.canonicalIntent() + "changed",
                        "GL_UNSUPPORTED_V9\nsynthetic-exact-tuple\n",
                        "GL_POSTING_INTENT_V1\ninvalid-" + (char) 0xd800)) {
            var bad =
                    new AccountingApprovalIntent(
                            TENANT,
                            BOOK,
                            "post",
                            original.intentDigest(),
                            bytes,
                            original.exactContext());
            assertThatThrownBy(() -> authority.authorize(bad, true).block())
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            denied -> assertThat(denied.getStatusCode().value()).isEqualTo(403));
        }
        assertThat(calls).hasValue(0);
        assertThat(authority.authorize(original, true).block().approval().intentDigest())
                .isEqualTo(original.intentDigest());
    }

    @Test
    void bookPermissionDoesNotImplyPermissionForAnyPostingAccount() {
        var p = actor(Instant.now().plusSeconds(60));
        var first = UUID.randomUUID().toString();
        var second = UUID.randomUUID().toString();
        var revoked = new java.util.concurrent.atomic.AtomicBoolean();
        var resources = new ArrayList<String>();
        var authority =
                authority(
                        p,
                        (actor, action, resource, context) -> {
                            resources.add(resource);
                            if (resource.equals("accounting/book/" + BOOK))
                                return Mono.just(Decision.permit());
                            assertThat(context.get("accountId")).isIn(first, second);
                            return Mono.just(
                                    revoked.get() && resource.equals("accounting/account/" + second)
                                            ? Decision.deny("withdrawn exact account")
                                            : Decision.permit());
                        },
                        (actor, i) -> Mono.just(proof(actor, i)),
                        null);
        assertThat(
                        authority
                                .authorizeAccounts(
                                        intent("post"), List.of(first, first, second), true)
                                .block())
                .isNotNull();
        assertThat(resources)
                .containsExactlyInAnyOrder(
                        "accounting/book/" + BOOK,
                        "accounting/account/" + first,
                        "accounting/account/" + second);
        revoked.set(true);
        assertThatThrownBy(
                        () ->
                                authority
                                        .authorizeAccounts(
                                                intent("post"), List.of(first, second), true)
                                        .block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        var bookOnly =
                authority(
                        p,
                        (actor, action, resource, context) ->
                                Mono.just(
                                        resource.equals("accounting/book/" + BOOK)
                                                ? Decision.permit()
                                                : Decision.deny("no account grant")),
                        (actor, i) -> Mono.just(proof(actor, i)),
                        null);
        assertThatThrownBy(
                        () ->
                                bookOnly.authorizeAccounts(intent("post"), List.of(first), true)
                                        .block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void resourceSetsAreBoundedCanonicalAndCannotOutliveTheirOverallBudget() {
        var p = actor(Instant.now().plusSeconds(60));
        var calls = new AtomicInteger();
        var authority =
                authority(
                        p,
                        (actor, action, resource, context) -> {
                            calls.incrementAndGet();
                            return Mono.just(Decision.permit());
                        },
                        (actor, i) -> Mono.just(proof(actor, i)),
                        null);
        var overflow = new ArrayList<String>();
        for (int i = 0; i < 501; i++) overflow.add(UUID.randomUUID().toString());
        for (var ids :
                List.of(
                        List.<String>of(),
                        List.of("12345678-1234-4234-8234-123456789ABC"),
                        overflow))
            assertThatThrownBy(() -> authority.authorizeAccounts(intent("post"), ids, true).block())
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThat(calls).hasValue(0);
        var stalled =
                authority(
                        p,
                        (actor, action, resource, context) ->
                                resource.equals("accounting/book/" + BOOK)
                                        ? Mono.just(Decision.permit())
                                        : Mono.never(),
                        (actor, i) -> Mono.just(proof(actor, i)),
                        null);
        reactor.test.StepVerifier.withVirtualTime(
                        () ->
                                stalled.authorizeAccounts(
                                        intent("post"),
                                        List.of(UUID.randomUUID().toString()),
                                        true))
                .thenAwait(java.time.Duration.ofSeconds(11))
                .expectErrorSatisfies(
                        error ->
                                assertThat(error)
                                        .isInstanceOfSatisfying(
                                                ResponseStatusException.class,
                                                e ->
                                                        assertThat(e.getStatusCode().value())
                                                                .isEqualTo(403)))
                .verify();
    }
}
