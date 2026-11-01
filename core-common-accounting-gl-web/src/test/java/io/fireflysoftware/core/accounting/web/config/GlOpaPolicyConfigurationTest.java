/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.web.config;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.util.List;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.PolicyEvaluationPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

class GlOpaPolicyConfigurationTest {
    @Test
    void disabledConfigurationAddsNoPolicyProvider() {
        new ApplicationContextRunner().withUserConfiguration(GlOpaPolicyConfiguration.class).run(context -> {
            assertThat(context.getBeansOfType(PolicyDecisionPort.class)).isEmpty();
            assertThat(context.getBeansOfType(PolicyEvaluationPort.class)).isEmpty();
        });
    }

    @Test
    void explicitSecureConfigurationSuppliesBothGenuinePolicyCapabilities() {
        new ApplicationContextRunner().withUserConfiguration(GlOpaPolicyConfiguration.class)
                .withBean(WebClient.Builder.class, WebClient::builder)
                .withPropertyValues("firefly.accounting.authorization.opa.enabled=true",
                        "firefly.accounting.authorization.opa.base-url=https://synthetic.policy.invalid",
                        "firefly.accounting.authorization.opa.decision-path=gl/allow",
                        "firefly.accounting.authorization.opa.evaluation-path=gl/current")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(PolicyDecisionPort.class)).hasSize(1);
                    assertThat(context.getBeansOfType(PolicyEvaluationPort.class)).hasSize(1);
                });
    }

    @Test
    void cleartextRequiresExplicitOwnedLoopbackAndOriginCannotCarryCredentialsOrRedirectPaths() {
        var configuration = new GlOpaPolicyConfiguration();
        assertThatCode(() -> configuration.policyConnection(WebClient.builder(), URI.create("https://synthetic.policy.invalid"), false))
                .doesNotThrowAnyException();
        assertThatCode(() -> configuration.policyConnection(WebClient.builder(), URI.create("http://127.0.0.1:12345"), true))
                .doesNotThrowAnyException();
        for (var origin : List.of("http://127.0.0.1:12345", "http://synthetic.policy.invalid", "https://user:secret@synthetic.policy.invalid",
                "https://synthetic.policy.invalid/path", "https://synthetic.policy.invalid?redirect=elsewhere", "https://synthetic.policy.invalid#fragment"))
            assertThatThrownBy(() -> configuration.policyConnection(WebClient.builder(), URI.create(origin), false))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.policyConnection(WebClient.builder(), URI.create("http://synthetic.policy.invalid"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
