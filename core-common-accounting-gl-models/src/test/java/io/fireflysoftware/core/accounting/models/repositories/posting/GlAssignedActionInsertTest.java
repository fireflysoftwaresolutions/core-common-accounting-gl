/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.models.repositories.posting;

import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.core.accounting.models.entities.posting.GlNativeJournalAction;
import io.fireflysoftware.core.accounting.models.repositories.posting.impl.GlNativePostingRepositoryImpl;
import io.r2dbc.spi.Statement;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Mono;

class GlAssignedActionInsertTest {
    static final UUID TENANT=GlNativeRepositoryTest.TENANT,BOOK=GlNativeRepositoryTest.BOOK;
    static GlNativeJournalAction row(Instant recorded) {
        return new GlNativeJournalAction(UUID.randomUUID(),TENANT,BOOK,"POST","a".repeat(64),"canonical",
                "b".repeat(64),"economic","c".repeat(64),new UUID[]{BOOK},1000L,UUID.randomUUID(),
                "https://synthetic.invalid","actor",1000L,UUID.randomUUID(),"GL_POSTING_REQUEST_V1","{}",recorded);
    }
    static class Driver extends GlNativeRepositoryTest.Driver {
        int generatedKeyRequests;RuntimeException writeFailure;
        @Override Statement statement(String query) {
            var original=super.statement(query);
            return (Statement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(p,m,a)->{
                if(m.getName().equals("returnGeneratedValues"))generatedKeyRequests++;
                if(m.getName().equals("execute")&&writeFailure!=null)return Mono.error(writeFailure);
                try{return m.invoke(original,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
        }
    }
    static class Repository extends GlNativePostingRepositoryImpl {
        final Driver driver;GlNativeJournalAction retained;int reads;UUID[] scope;
        Repository(Driver driver){super(driver.template());this.driver=driver;}
        @Override public Mono<GlNativeJournalAction> findAction(UUID tenant,UUID book,UUID action){
            assertEquals(1,driver.sql.size());reads++;scope=new UUID[]{tenant,book,action};return Mono.justOrEmpty(retained);
        }
    }
    @Test void mappedAssignedInsertRequiresOneWriteAndReturnsProtectedReadback() {
        var driver=new Driver();var repository=new Repository(driver);var proposed=row(null);var retained=new GlNativeJournalAction(proposed.actionId(),proposed.tenantId(),proposed.bookId(),proposed.kind(),proposed.intentDigest(),proposed.canonicalIntent(),
                proposed.economicDigest(),proposed.canonicalEconomicIntent(),proposed.sourceClaimKey(),proposed.accountIds(),proposed.notAfterEpochMillis(),proposed.receiptId(),
                proposed.actorIssuer(),proposed.actorSubject(),proposed.authorityUntilEpochMillis(),proposed.postingId(),proposed.requestShape(),proposed.originalRequest(),Instant.now());repository.retained=retained;
        assertSame(retained,repository.appendAction(proposed).block());assertArrayEquals(new UUID[]{TENANT,BOOK,proposed.actionId()},repository.scope);
        assertEquals(0,driver.generatedKeyRequests);assertEquals(1,repository.reads);
        var sql=driver.sql.getFirst();assertTrue(sql.startsWith("INSERT INTO \"public\".\"gl_native_journal_action\""));
        assertTrue(sql.contains("action_id"));assertTrue(sql.contains("original_request"));assertFalse(sql.contains("RETURNING"));assertFalse(sql.contains("recorded_at"));
    }
    @Test void incorrectAffectedCountCannotReadOrReportAnInsertedAction() {
        for(long count:new long[]{0,2}){
            var driver=new Driver();driver.rows=count;var repository=new Repository(driver);repository.retained=row(Instant.now());
            assertThrows(DataIntegrityViolationException.class,()->repository.appendAction(row(null)).block());assertEquals(0,repository.reads);
        }
    }
    @Test void missingProtectedReadbackCannotReturnTheProposedAction() {
        var driver=new Driver();var repository=new Repository(driver);
        assertThrows(DataIntegrityViolationException.class,()->repository.appendAction(row(null)).block());assertEquals(1,repository.reads);
    }
    @Test void rejectedInsertionCannotReadOrReturnAnExistingActionAsReplay() {
        var driver=new Driver();var rejection=new org.springframework.dao.DuplicateKeyException("synthetic duplicate");driver.writeFailure=rejection;
        var repository=new Repository(driver);repository.retained=row(Instant.now());
        assertSame(rejection,assertThrows(org.springframework.dao.DuplicateKeyException.class,()->repository.appendAction(row(null)).block()));assertEquals(0,repository.reads);
    }
}
