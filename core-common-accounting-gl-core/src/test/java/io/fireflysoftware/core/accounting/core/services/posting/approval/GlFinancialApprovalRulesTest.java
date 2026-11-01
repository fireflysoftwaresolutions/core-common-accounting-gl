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
package io.fireflysoftware.core.accounting.core.services.posting.approval;

import static org.assertj.core.api.Assertions.*;

import io.fireflysoftware.core.accounting.models.entities.approval.GlFinancialApprovalControl;
import io.fireflysoftware.core.accounting.models.entities.approval.GlFinancialApprovalDefinition;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.fireflyframework.kernel.audit.AuditActor;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class GlFinancialApprovalRulesTest {
    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final String TENANT = UUID.randomUUID().toString();
    private static final UUID STAFF = UUID.randomUUID();
    private static final String ISSUER = "https://synthetic.identity.invalid";

    private static AuditActor actor(String issuer, String subject, String tenant, UUID staff) {
        return new AuditActor(issuer, subject, "synthetic-finance", tenant, "synthetic-staff", staff);
    }

    private static GlFinancialApprovalDefinition definition() {
        return new GlFinancialApprovalDefinition(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "{}", "retained-full-intent", "a".repeat(64), "retained-economic-intent",
                "b".repeat(64), "retained-source-claim", ISSUER, "maker", "synthetic-finance",
                TENANT, "synthetic-staff", STAFF, NOW.plusSeconds(600).toEpochMilli(),
                UUID.randomUUID(), "c".repeat(64));
    }

    private static GlFinancialApprovalControl control(
            GlFinancialApprovalDefinition definition, String state, long revision) {
        return new GlFinancialApprovalControl(
                definition.id(), definition.tenantId(), definition.bookId(), state, revision,
                null, null, null, null, null, UUID.randomUUID(), "d".repeat(64), "e".repeat(64));
    }

    private static void refused(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void changingStaffIdentityCannotMakeTheSameSignedSubjectAnIndependentChecker() {
        var definition = definition();
        refused(() -> GlFinancialApprovalRules.checker(
                definition, actor(ISSUER, "maker", TENANT, UUID.randomUUID())));
    }

    @Test
    void changingIssuerAndSubjectCannotMakeTheSameCanonicalStaffAnIndependentChecker() {
        var definition = definition();
        refused(() -> GlFinancialApprovalRules.checker(definition,
                actor("https://synthetic.other-issuer.invalid", "another-subject", TENANT, STAFF)));
    }

    @Test
    void independentCheckerMustStillBelongToTheMakersPlatformTenant() {
        var definition = definition();
        var differentStaff = UUID.randomUUID();
        assertThatCode(() -> GlFinancialApprovalRules.checker(
                definition, actor(ISSUER, "checker", TENANT, differentStaff))).doesNotThrowAnyException();
        refused(() -> GlFinancialApprovalRules.checker(
                definition, actor(ISSUER, "checker", UUID.randomUUID().toString(), differentStaff)));
    }

    @Test
    void makerReplaysRequireTheOriginalSubjectAndCanonicalStaffTogether() {
        var definition = definition();
        assertThatCode(() -> GlFinancialApprovalRules.maker(
                definition, actor(ISSUER, "maker", TENANT, STAFF))).doesNotThrowAnyException();
        refused(() -> GlFinancialApprovalRules.maker(
                definition, actor(ISSUER, "maker", TENANT, UUID.randomUUID())));
        refused(() -> GlFinancialApprovalRules.maker(
                definition, actor(ISSUER, "different-subject", TENANT, STAFF)));
        refused(() -> GlFinancialApprovalRules.maker(
                definition, actor(ISSUER, "maker", UUID.randomUUID().toString(), STAFF)));
    }

    @Test
    void durableBusinessApprovalCanOutliveEachFiveSecondCommand() {
        long requested = NOW.plusSeconds(600).toEpochMilli();
        assertThat(GlFinancialApprovalRules.businessUntil(
                requested, NOW.plusSeconds(3600).toEpochMilli(), NOW, Duration.ofMinutes(15)))
                .isEqualTo(requested);
    }

    @Test
    void durableApprovalCannotExceedItsOriginalCutoffOrConfiguredMaximum() {
        long cutoff = NOW.plusSeconds(3600).toEpochMilli();
        refused(() -> GlFinancialApprovalRules.businessUntil(
                NOW.toEpochMilli(), cutoff, NOW, Duration.ofMinutes(15)));
        refused(() -> GlFinancialApprovalRules.businessUntil(
                NOW.plusSeconds(901).toEpochMilli(), cutoff, NOW, Duration.ofMinutes(15)));
        refused(() -> GlFinancialApprovalRules.businessUntil(
                NOW.plusSeconds(600).toEpochMilli(), NOW.plusSeconds(599).toEpochMilli(),
                NOW, Duration.ofMinutes(15)));
    }

    @Test
    void expiredApprovalIsRefusedEvenWhenItsLastControlWasApproved() {
        var definition = definition();
        var control = control(definition, "APPROVED", 1);
        assertThatCode(() -> GlFinancialApprovalRules.current(definition, control, NOW))
                .doesNotThrowAnyException();
        refused(() -> GlFinancialApprovalRules.current(definition, control, NOW.plusSeconds(600)));
    }

    @Test
    void unknownLifecycleAndRevisionsCannotBecomeCurrentAuthority() {
        var definition = definition();
        refused(() -> GlFinancialApprovalRules.current(definition, control(definition, "POSTED", 1), NOW));
        refused(() -> GlFinancialApprovalRules.current(definition, control(definition, "APPROVED", -1), NOW));
        refused(() -> GlFinancialApprovalRules.current(definition, control(definition, "APPROVED", 3), NOW));
    }
}
