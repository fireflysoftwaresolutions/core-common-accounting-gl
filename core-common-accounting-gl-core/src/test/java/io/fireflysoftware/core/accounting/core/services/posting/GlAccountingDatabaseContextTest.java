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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.r2dbc.spi.ConnectionFactory;

import org.fireflyframework.security.api.domain.Decision;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionContextManager;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

class GlAccountingDatabaseContextTest {
    static final String ACTION = UUID.randomUUID().toString();
    static final String ACCOUNT = UUID.randomUUID().toString();

    static AccountingApprovalIntent intent(String action) {
        String canonical =
                GlPostingRules.canonical(
                        "GL_POSTING_INTENT_V1",
                        List.of(
                                GlAccountingAuthorityTest.TENANT,
                                GlAccountingAuthorityTest.BOOK,
                                action));
        return new AccountingApprovalIntent(
                GlAccountingAuthorityTest.TENANT,
                GlAccountingAuthorityTest.BOOK,
                "post",
                GlPostingRules.digest(canonical),
                canonical,
                Map.of("actionId", action));
    }

    static GlAccountingAuthority authority() {
        return GlAccountingAuthorityTest.authority(
                GlAccountingAuthorityTest.actor(Instant.now().plusSeconds(60)),
                (a, b, c, d) -> Mono.just(Decision.permit()),
                (p, i) -> Mono.just(GlAccountingAuthorityTest.proof(p, i)),
                null);
    }

    static void denied(Mono<?> call, int status) {
        assertThatThrownBy(call::block)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }

    static Mono<Void> inContext(
            DatabaseClient db,
            GlAccountingDatabaseContext context,
            GlAccountingAuthority.Admission admission,
            boolean bindConnection) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .flatMap(
                        manager -> {
                            manager.setActualTransactionActive(true);
                            if (bindConnection)
                                manager.bindResource(db.getConnectionFactory(), new Object());
                            return context.establish(admission);
                        })
                .contextWrite(TransactionContextManager.createTransactionContext());
    }

    @Test
    void standaloneContextCannotSetSessionStateOutsideNativeTransaction() {
        var db = mock(DatabaseClient.class);
        var context = new GlAccountingDatabaseContext(db);
        var admitted =
                authority().authorizeAccounts(intent(ACTION), List.of(ACCOUNT), true).block();
        denied(context.establish(admitted), 503);
        verify(db, never()).sql(anyString());
    }

    @Test
    void unrelatedReactiveTransactionCannotConfigureAnotherDatabaseConnection() {
        var db = mock(DatabaseClient.class);
        when(db.getConnectionFactory()).thenReturn(mock(ConnectionFactory.class));
        var context = new GlAccountingDatabaseContext(db);
        var admitted =
                authority().authorizeAccounts(intent(ACTION), List.of(ACCOUNT), true).block();
        denied(inContext(db, context, admitted, false), 503);
        verify(db, never()).sql(anyString());
    }

    @Test
    void bookOnlyPolicyCannotEstablishFinancialAccountAuthority() {
        var db = mock(DatabaseClient.class);
        when(db.getConnectionFactory()).thenReturn(mock(ConnectionFactory.class));
        var context = new GlAccountingDatabaseContext(db);
        var bookOnly = authority().authorize(intent(ACTION), true).block();
        denied(inContext(db, context, bookOnly, true), 403);
        verify(db, never()).sql(anyString());
    }

    @Test
    void actionContextCannotDifferFromTheApprovedCanonicalActionTuple() {
        var db = mock(DatabaseClient.class);
        when(db.getConnectionFactory()).thenReturn(mock(ConnectionFactory.class));
        var context = new GlAccountingDatabaseContext(db);
        var original = intent(ACTION);
        var changed =
                new AccountingApprovalIntent(
                        original.tenantId(),
                        original.bookId(),
                        original.operation(),
                        original.intentDigest(),
                        original.canonicalIntent(),
                        Map.of("actionId", UUID.randomUUID().toString()));
        var admitted = authority().authorizeAccounts(changed, List.of(ACCOUNT), true).block();
        denied(inContext(db, context, admitted, true), 403);
        verify(db, never()).sql(anyString());
    }
}
