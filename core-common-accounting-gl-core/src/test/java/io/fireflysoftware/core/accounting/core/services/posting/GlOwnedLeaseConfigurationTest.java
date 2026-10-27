/* Copyright 2026 Firefly Software Foundation. Licensed under Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

import io.fireflysoftware.core.accounting.models.repositories.posting.impl.GlNativePostingRepositoryImpl;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.annotation.*;
import org.springframework.data.auditing.ReactiveIsNewAwareAuditingHandler;
import org.springframework.data.mapping.context.PersistentEntities;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback;
import org.springframework.data.r2dbc.mapping.event.ReactiveAuditingEntityCallback;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

class GlOwnedLeaseConfigurationTest {
    record AuditProbe(@Id UUID id, @CreatedDate Instant createdAt, @CreatedBy String createdBy) {}
    static final Instant RECORDED = Instant.parse("2026-10-26T00:00:00Z");
    static final class Probe implements BeforeConvertCallback<AuditProbe>, Ordered {
        final AtomicInteger calls = new AtomicInteger();
        final IllegalStateException stop = new IllegalStateException("source callback stops before acquisition");
        @Override public int getOrder() { return 200; }
        @Override public Mono<AuditProbe> onBeforeConvert(AuditProbe value, SqlIdentifier table) {
            assertEquals(RECORDED, value.createdAt()); assertEquals("source-auditor", value.createdBy()); calls.incrementAndGet(); return Mono.error(stop);
        }
    }
    static Object field(Object value, String name) throws Exception { var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value); }
    @Test void configuredNativeTemplateRetainsConverterAuditingCallbacksAndEveryFactoryIdentity() throws Exception {
        var driver = new GlBeginLeaseTest.Driver(); var original = new R2dbcEntityTemplate(driver); var probe = new Probe();
        var handler = new ReactiveIsNewAwareAuditingHandler(PersistentEntities.of(original.getConverter().getMappingContext()));
        handler.setAuditorAware(() -> Mono.just("source-auditor")); handler.setDateTimeProvider(() -> Optional.of(RECORDED));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("source-enabled", Map.of("firefly.accounting.native-posting.enabled", "true")));
            context.registerBean("r2dbcEntityTemplate", R2dbcEntityTemplate.class, () -> original);
            context.registerBean("sourceAuditing", ReactiveAuditingEntityCallback.class, () -> new ReactiveAuditingEntityCallback(() -> handler));
            context.registerBean("sourceProbe", Probe.class, () -> probe);
            context.register(GlNativePostingConfiguration.class, GlNativePostingRepositoryImpl.class); context.refresh();
            assertTrue(context.containsBean("glNativePostingTemplate"), "Dedicated managed native template required");
            var nativeTemplate = context.getBean("glNativePostingTemplate", R2dbcEntityTemplate.class);
            assertSame(original.getConverter(), nativeTemplate.getConverter()); assertSame(original.getDataAccessStrategy(), nativeTemplate.getDataAccessStrategy());
            var cf = nativeTemplate.getDatabaseClient().getConnectionFactory(); assertNotSame(driver, cf);
            var tx=context.getBean("glNativePostingTransaction", GlAccountingTransaction.class);
            assertSame(cf, ((R2dbcTransactionManager) field(tx,"manager")).getConnectionFactory());
            var database=(org.springframework.r2dbc.core.DatabaseClient) field(field(tx,"context"),"database"); assertSame(cf,database.getConnectionFactory());
            assertSame(nativeTemplate,field(context.getBean("glNativePostingRepository"),"template"));
            for (var template : List.of(original, nativeTemplate)) StepVerifier.create(template.insert(new AuditProbe(null,null,null))).expectErrorMatches(e -> e==probe.stop).verify(Duration.ofSeconds(1));
            assertEquals(2,probe.calls.get()); assertEquals(0,driver.creates);
        }
    }
    @Test void nativeConfigurationRefusesAnUnprotectedTemplateBeforeAcquisition() {
        var driver=new GlBeginLeaseTest.Driver();var original=new R2dbcEntityTemplate(driver);
        assertThrows(IllegalStateException.class,()->new GlNativePostingConfiguration().transaction(original));assertEquals(0,driver.creates);
    }
    @Test void mismatchedManagerAndProtectedContextFactoriesAreRejectedBeforeAcquisition() {
        var own=new GlBeginLeaseTest.Driver();var foreign=new GlBeginLeaseTest.Driver();
        var template=new GlNativePostingConfiguration().nativeTemplate(new R2dbcEntityTemplate(own));
        assertThrows(IllegalStateException.class,()->new GlAccountingTransaction(new R2dbcTransactionManager(foreign),new GlAccountingDatabaseContext(template.getDatabaseClient()),template));
        assertEquals(0,own.creates);assertEquals(0,foreign.creates);
    }
    @Test void dedicatedNativeCompositionRemainsDefaultOffWithoutAcquisition() {
        var driver=new GlBeginLeaseTest.Driver();
        try(var context=new AnnotationConfigApplicationContext()) {
            context.registerBean("r2dbcEntityTemplate",R2dbcEntityTemplate.class,()->new R2dbcEntityTemplate(driver));
            context.register(GlNativePostingConfiguration.class,GlNativePostingRepositoryImpl.class);context.refresh();
            assertFalse(context.containsBean("glNativePostingTemplate"));assertFalse(context.containsBean("glNativePostingTransaction"));assertFalse(context.containsBean("glNativePostingRepository"));assertEquals(0,driver.creates);
        }
    }
}
