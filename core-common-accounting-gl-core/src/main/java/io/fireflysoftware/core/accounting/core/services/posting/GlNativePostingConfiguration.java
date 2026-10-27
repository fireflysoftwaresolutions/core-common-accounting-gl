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

import org.fireflyframework.core.transaction.OwnedLeaseConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "firefly.accounting.native-posting.enabled", havingValue = "true")
public class GlNativePostingConfiguration {
    @Bean("glNativePostingTemplate")
    public R2dbcEntityTemplate nativeTemplate(@Qualifier("r2dbcEntityTemplate") R2dbcEntityTemplate original) {
        var factory=new OwnedLeaseConnectionFactory(original.getDatabaseClient().getConnectionFactory());
        var client=org.springframework.r2dbc.core.DatabaseClient.create(factory);
        return new R2dbcEntityTemplate(client,original.getDataAccessStrategy());
    }

    @Bean("glNativePostingTransaction")
    public GlAccountingTransaction transaction(
            @Qualifier("glNativePostingTemplate") R2dbcEntityTemplate template) {
        var client = template.getDatabaseClient();
        if(!(client.getConnectionFactory() instanceof OwnedLeaseConnectionFactory))
            throw new IllegalStateException("Protected native template required for native posting composition");
        return new GlAccountingTransaction(
                new R2dbcTransactionManager(client.getConnectionFactory()),
                new GlAccountingDatabaseContext(client),template);
    }
}
