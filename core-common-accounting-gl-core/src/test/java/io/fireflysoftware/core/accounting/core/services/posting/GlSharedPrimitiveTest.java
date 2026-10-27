package io.fireflysoftware.core.accounting.core.services.posting;

import io.r2dbc.spi.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.fireflyframework.core.transaction.OwnedLeaseConnectionFactory;
import org.reactivestreams.Publisher;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.*;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

class GlSharedPrimitiveTest {
    static final class Fixture implements ConnectionFactory {
        final GlBeginLeaseTest.Driver driver=new GlBeginLeaseTest.Driver();
        final GlAccountingAuthority.Admission admission=GlNativeContextTest.admission();
        final AtomicLong nanos=new AtomicLong(),epoch=new AtomicLong(System.currentTimeMillis());
        boolean failRollback,expireAfterClose;
        Fixture(){driver.stallBegin=false;driver.statements.nativeRow.putAll(GlNativeContextTest.driver(admission).nativeRow);}
        public Publisher<? extends Connection> create(){return Mono.from(driver.create()).map(raw->(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("rollbackTransaction")&&failRollback)return Mono.error(new IllegalStateException("source rollback unavailable"));
            if(m.getName().equals("close"))return Mono.from(raw.close()).doOnSuccess(v->{if(expireAfterClose)epoch.addAndGet(30000);});
            try{return m.invoke(raw,a);}catch(InvocationTargetException error){throw error.getCause();}
        }));}
        public ConnectionFactoryMetadata getMetadata(){return driver.getMetadata();}
        GlAccountingTransaction transaction(){
            var template=new GlNativePostingConfiguration().nativeTemplate(new R2dbcEntityTemplate(this));
            var manager=new R2dbcTransactionManager(template.getDatabaseClient().getConnectionFactory());var context=new GlAccountingDatabaseContext(template.getDatabaseClient());
            try {
                var ctor=GlAccountingTransaction.class.getDeclaredConstructor(ReactiveTransactionManager.class,GlAccountingDatabaseContext.class,R2dbcEntityTemplate.class,LongSupplier.class,LongSupplier.class);
                return ctor.newInstance(manager,context,template,(LongSupplier)nanos::get,(LongSupplier)epoch::get);
            }catch(NoSuchMethodException legacy){return new GlAccountingTransaction(manager,context,nanos::get,epoch::get);}
            catch(ReflectiveOperationException error){throw new AssertionError(error);}
        }
    }
    @Test void managedNativePathMustUseTheActualSharedFactory(){
        var driver=new GlBeginLeaseTest.Driver();var template=new GlNativePostingConfiguration().nativeTemplate(new R2dbcEntityTemplate(driver));
        assertInstanceOf(OwnedLeaseConnectionFactory.class,template.getDatabaseClient().getConnectionFactory());assertEquals(0,driver.creates);
    }
    @Test void budgetAlreadyExpiredBeforeAcquisitionProducesOnlySafeDomainUncertainty(){
        var f=new Fixture();f.epoch.set(f.admission.validUntilEpochMillis()+1);
        var observed=assertThrows(IllegalStateException.class,()->f.transaction().executeWitnessed(f.admission,s->Mono.just("protected-value")).block(Duration.ofSeconds(2)));
        assertEquals("CompletionUncertain",observed.getClass().getSimpleName());assertEquals(0,f.driver.creates);assertEquals(0,f.driver.closes);
    }
    @Test void originalDomainErrorIdentityIsRestoredOnlyAfterConfirmedRollbackAndCompleteCleanup(){
        var f=new Fixture();var failure=new IllegalStateException("source domain refusal");
        assertSame(failure,assertThrows(IllegalStateException.class,()->f.transaction().executeWitnessed(f.admission,s->Mono.error(failure)).block(Duration.ofSeconds(2))));
        assertEquals(1,f.driver.closes);assertEquals(0,f.driver.commits);
    }
    @Test void rollbackFailureCannotRestoreTheOriginalDomainError(){
        var f=new Fixture();f.failRollback=true;var failure=new IllegalStateException("protected-value");
        var observed=assertThrows(IllegalStateException.class,()->f.transaction().executeWitnessed(f.admission,s->Mono.error(failure)).block(Duration.ofSeconds(2)));
        assertEquals("CompletionUncertain",observed.getClass().getSimpleName());assertNotSame(failure,observed);assertFalse(observed.toString().contains("protected-value"));assertEquals(1,f.driver.closes);
    }
    @Test void commitAcknowledgmentWithoutBoundaryCompletionMustRemainUncertainForGl(){
        var f=new Fixture();
        var observed=assertThrows(IllegalStateException.class,()->f.transaction().executeWitnessed(f.admission,s->
                TransactionSynchronizationManager.forCurrentTransaction().flatMap(sync->{sync.registerSynchronization(new TransactionSynchronization(){@Override public Mono<Void> afterCommit(){return Mono.error(new IllegalStateException("source after-commit unavailable"));}});return Mono.just("protected-value");})).block(Duration.ofSeconds(2)));
        assertEquals("CompletionUncertain",observed.getClass().getSimpleName());assertFalse(observed.toString().contains("protected-value"));assertEquals(1,f.driver.commits);assertEquals(1,f.driver.closes);
    }
    @Test void completedBoundaryWithFailedCloseKeepsOnlySafeKnownCompletion(){
        var ref=new AtomicReference<Fixture>();
        StepVerifier.withVirtualTime(()->{var f=new Fixture();ref.set(f);f.driver.stallClose=true;return f.transaction().executeWitnessed(f.admission,s->Mono.just("protected-value"));})
            .thenAwait(Duration.ofSeconds(3)).expectErrorSatisfies(error->{assertEquals("CompletionKnown",error.getClass().getSimpleName());assertFalse(error.toString().contains("protected-value"));assertNull(error.getCause());}).verify(Duration.ofSeconds(1));
        assertEquals(1,ref.get().driver.commits);assertEquals(1,ref.get().driver.closes);
    }
    @Test void expiryAfterSuccessfulCloseWithholdsValueButPreservesCompletedBoundary(){
        var f=new Fixture();f.expireAfterClose=true;
        var observed=assertThrows(IllegalStateException.class,()->f.transaction().executeWitnessed(f.admission,s->Mono.just("protected-value")).block(Duration.ofSeconds(2)));
        assertEquals("CompletionKnown",observed.getClass().getSimpleName());assertFalse(observed.toString().contains("protected-value"));assertEquals(1,f.driver.commits);assertEquals(1,f.driver.closes);
    }
}
