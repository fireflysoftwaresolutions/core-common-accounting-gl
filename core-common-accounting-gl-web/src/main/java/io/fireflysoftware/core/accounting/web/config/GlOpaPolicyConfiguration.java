/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.web.config;

import java.net.URI;
import java.util.Set;
import org.fireflyframework.security.adapter.opa.OpaPolicyDecisionAdapter;
import org.fireflyframework.security.adapter.opa.OpaPolicyEvaluationAdapter;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.PolicyEvaluationPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Explicit real policy-engine composition; the default strict security chain remains authoritative. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "firefly.accounting.authorization.opa", name = "enabled", havingValue = "true")
public class GlOpaPolicyConfiguration {
    public record OpaConnection(WebClient client) {}

    @Bean("glOpaPolicyConnection")
    public OpaConnection policyConnection(WebClient.Builder builder,
            @Value("${firefly.accounting.authorization.opa.base-url}") URI base,
            @Value("${firefly.accounting.authorization.opa.allow-loopback-http:false}") boolean loopbackHttp) {
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))
                || !("https".equals(base.getScheme()) || loopbackHttp && "http".equals(base.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(base.getHost())))
            throw new IllegalArgumentException("Explicit secure OPA origin required");
        return new OpaConnection(builder.clone().baseUrl(base.toString()).build());
    }

    @Bean
    public PolicyDecisionPort glUrlPolicyDecision(@Qualifier("glOpaPolicyConnection") OpaConnection connection,
            @Value("${firefly.accounting.authorization.opa.decision-path}") String decisionPath) {
        return new OpaPolicyDecisionAdapter(connection.client(), path(decisionPath));
    }

    @Bean
    public PolicyEvaluationPort glVersionedPolicyEvaluation(@Qualifier("glOpaPolicyConnection") OpaConnection connection,
            @Value("${firefly.accounting.authorization.opa.evaluation-path}") String evaluationPath) {
        return new OpaPolicyEvaluationAdapter(connection.client(), path(evaluationPath));
    }

    private static String path(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*") || value.length() > 255)
            throw new IllegalArgumentException("Explicit bounded OPA decision path required");
        return value;
    }
}
