package io.fireflysoftware.core.accounting.web.config.posting.operatorfixture;

import org.fireflyframework.security.adapter.opa.OpaPolicyDecisionAdapter;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Source-test operator extension discovered by the actual production package scanner. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "qualified.operator-policy.enabled", havingValue = "true")
public class OperatorPolicyComponent {
    @Bean public PolicyDecisionPort operatorPolicyBackend() {
        return new OpaPolicyDecisionAdapter(WebClient.builder().baseUrl("https://policy.example.invalid").build(),
                "firefly/accounting/allow");
    }
}
