/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.fireflysoftware.core.accounting.models.repositories.posting.GlNativePostingResources;
import io.r2dbc.spi.ConnectionFactory;
import org.fireflyframework.core.transaction.OwnedLeaseConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/** Genuine Boot data auto-configuration must retain its sole ordinary template. No SQL is executed. */
class GlNativePostingCompositionTest {
    @Configuration(proxyBeanMethods=false)
    @org.springframework.boot.autoconfigure.AutoConfigurationPackage(basePackages="io.fireflysoftware.core.accounting.models")
    static class DatabaseFixture {
        @Bean DatabaseClient databaseClient(){
            var factory=mock(ConnectionFactory.class);when(factory.getMetadata()).thenReturn(()->"PostgreSQL");
            when(factory.create()).thenReturn(Mono.error(new AssertionError("Composition must not acquire SQL connections")));
            return DatabaseClient.create(factory);
        }
    }
    @Test void nativeOwnerResourcesDoNotSuppressOrAmbiguateTheOriginalBootTemplate(){
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataR2dbcAutoConfiguration.class))
                .withUserConfiguration(DatabaseFixture.class,GlNativePostingConfiguration.class)
                .withPropertyValues("firefly.accounting.native-posting.enabled=true").run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(R2dbcEntityTemplate.class).hasSingleBean(GlNativePostingResources.class);
                    var ordinary=context.getBean("r2dbcEntityTemplate",R2dbcEntityTemplate.class);var owner=context.getBean(GlNativePostingResources.class).template();
                    assertThat(owner).isNotSameAs(ordinary);assertThat(owner.getDataAccessStrategy()).isSameAs(ordinary.getDataAccessStrategy());
                    assertThat(owner.getDatabaseClient().getConnectionFactory()).isInstanceOf(OwnedLeaseConnectionFactory.class);
                    assertThat(context.getBean("glNativePostingTransaction")).isInstanceOf(GlAccountingTransaction.class);
                });
    }
}
