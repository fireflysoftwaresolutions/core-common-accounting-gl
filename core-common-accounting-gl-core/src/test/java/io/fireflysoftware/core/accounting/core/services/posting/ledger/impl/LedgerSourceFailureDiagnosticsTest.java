/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.ledger.impl;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.TimeoutException;

class LedgerSourceFailureDiagnosticsTest {
    private static final String SENSITIVE="Bearer synthetic-secret original-financial-request-body";

    @Test void diagnosticNeverRendersThrowableMessagesOrNestedPayloads() {
        var error=new IllegalStateException(SENSITIVE,new IllegalArgumentException(SENSITIVE));
        for(var stage:SdkLedgerPostingSourcePortImpl.Stage.values()) {
            String value=SdkLedgerPostingSourcePortImpl.failureDescription(stage,error);
            assertFalse(value.contains(SENSITIVE));
            assertFalse(value.contains("Bearer"));
            assertTrue(value.contains("stage="+stage.name()));
            assertTrue(value.contains("exception=java.lang.IllegalStateException"));
        }
    }

    @Test void knownOwnerRefusalPreservesTheExactExistingOutcome() {
        for(var status:new HttpStatus[]{HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.CONFLICT,HttpStatus.BAD_GATEWAY,HttpStatus.SERVICE_UNAVAILABLE}) {
            var error=new ResponseStatusException(status,SENSITIVE);
            assertSame(error,SdkLedgerPostingSourcePortImpl.mapFailure(SdkLedgerPostingSourcePortImpl.Stage.POLICY_INITIAL,error));
            String description=SdkLedgerPostingSourcePortImpl.failureDescription(SdkLedgerPostingSourcePortImpl.Stage.POLICY_INITIAL,error);
            assertTrue(description.contains("status="+status.value()));
            assertFalse(description.contains(SENSITIVE));
        }
    }

    @Test void unknownFailureKeepsUnavailableOutcomeWithoutLeakingItsCause() {
        var mapped=assertInstanceOf(ResponseStatusException.class,SdkLedgerPostingSourcePortImpl.mapFailure(
                SdkLedgerPostingSourcePortImpl.Stage.LEDGER_HTTP,new IllegalStateException(SENSITIVE)));
        assertEquals(503,mapped.getStatusCode().value());
        assertEquals("Original Ledger monetary evidence unavailable",mapped.getReason());
        assertNull(mapped.getCause());
    }

    @Test void timeoutRemainsUncertainAndHasOnlyBoundedClassification() {
        var error=new TimeoutException(SENSITIVE);
        String description=SdkLedgerPostingSourcePortImpl.failureDescription(SdkLedgerPostingSourcePortImpl.Stage.OBSERVER_CREDENTIAL,error);
        assertEquals("stage=OBSERVER_CREDENTIAL category=TIMEOUT exception=java.util.concurrent.TimeoutException status=0",description);
        var mapped=assertInstanceOf(ResponseStatusException.class,SdkLedgerPostingSourcePortImpl.mapFailure(SdkLedgerPostingSourcePortImpl.Stage.OBSERVER_CREDENTIAL,error));
        assertEquals(503,mapped.getStatusCode().value());
    }
}
