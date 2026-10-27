/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.web.config.posting;

import io.fireflysoftware.core.accounting.core.config.posting.GlBankOwnerObservationConfiguration;
import io.fireflysoftware.core.accounting.core.services.posting.GlBankOwnerObservationPort;
import io.fireflysoftware.core.accounting.core.services.posting.impl.SdkOrganizationBankOwnerObservationAdapter;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.client.WebClient;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class GlBankOwnerProductionSecurityTest {
    static final String LOCAL = "io.fireflysoftware.core.accounting.web.config.posting.GlBankOwnerPolicyAutoConfiguration";
    static final String RS = "org.fireflyframework.security.rs.ResourceServerAutoConfiguration";
    static final String OAUTH = "org.fireflyframework.security.client.OAuth2ClientAutoConfiguration";
    static final String NATIVE = "org.springframework.boot.security.oauth2.client.autoconfigure.reactive.ReactiveOAuth2ClientAutoConfiguration";
    static final String WEB = "core-common-accounting-gl-web";
    static final Path ROOT = projectSourceRoot();

    private static Path projectSourceRoot() {
        String configured = System.getProperty("source.root");
        if (configured != null) return Path.of(configured).toAbsolutePath().normalize();
        Path base = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        if (java.nio.file.Files.isDirectory(base.resolve(WEB))) return base;
        Path parent = base.getParent();
        if (parent != null && java.nio.file.Files.isDirectory(parent.resolve(WEB))) return parent;
        throw new IllegalStateException("GL project source directory could not be located");
    }

    static Class<?> required(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException error) { throw new AssertionError("Required production configuration is absent: " + name, error); }
    }

    ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(required(RS), required(OAUTH), required(NATIVE), required(LOCAL),
                        required("org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration"),
                        required("org.springframework.boot.security.oauth2.client.autoconfigure.reactive.ReactiveOAuth2ClientWebSecurityAutoConfiguration")))
                .withUserConfiguration(GlBankOwnerObservationConfiguration.class)
                .withInitializer(context -> {
                    try {
                        var sources = new YamlPropertySourceLoader().load("actual-gl-defaults",
                                new FileSystemResource(ROOT.resolve(WEB + "/src/main/resources/application.yaml")));
                        context.getEnvironment().getPropertySources().addLast(sources.getFirst());
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                });
    }

    String[] profile(boolean registration, boolean policy) {
        var values = new ArrayList<String>();
        values.add("firefly.accounting.bank-owner-observation.enabled=true");
        values.add("firefly.security.resource-server.strict-policy=true");
        String p = "firefly.accounting.bank-owner-observation.profiles[0].";
        for (String name : List.of("tenant-id", "bank-id", "enrollment-id", "institution-id", "issuer-authority-id"))
            values.add(p + "scope." + name + "=00000000-0000-0000-0000-000000000001");
        values.add(p + "scope.version=1");
        values.add(p + "registration-id=gl-bank-owner");
        values.add(p + "platform-tenant-id=00000000-0000-0000-0000-000000000002");
        values.add(p + "base-uri=https://organization.example.invalid");
        values.add(p + "token-uri=https://issuer.example.invalid/organization/oauth/token");
        values.add(p + "jwks-uri=https://issuer.example.invalid/organization/jwks");
        values.add(p + "issuer=https://issuer.example.invalid/organization");
        values.add(p + "audience=organization-observation");
        values.add(p + "subject=gl-bank-owner-observer");
        values.add(p + "client-id=gl-bank-owner");
        values.add(p + "tenant-claim=tenant_id");
        values.add(p + "kind-claim=principal_kind");
        values.add(p + "machine-value=MACHINE");
        values.add(p + "client-claim=client_id");
        values.add(p + "staff-claim=staff_actor_id");
        if (registration) {
            String r = "spring.security.oauth2.client.registration.gl-bank-owner.";
            values.add(r + "provider=organization-owner");
            values.add(r + "client-id=gl-bank-owner");
            values.add(r + "client-secret=" + UUID.randomUUID());
            values.add(r + "authorization-grant-type=client_credentials");
            values.add(r + "client-authentication-method=client_secret_basic");
            values.add("spring.security.oauth2.client.provider.organization-owner.token-uri=https://issuer.example.invalid/organization/oauth/token");
        }
        if (policy) {
            values.add("firefly.accounting.bank-owner-observation.policy.base-uri=https://policy.example.invalid");
            values.add("firefly.accounting.bank-owner-observation.policy.decision-path=firefly/accounting/allow");
        }
        return values.toArray(String[]::new);
    }

    @Test void productionPomDeclaresManagedSecurityModules() throws Exception {
        var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ROOT.resolve(WEB + "/pom.xml").toFile());
        var dependencies = xml.getElementsByTagName("dependency");
        for (String artifact : List.of("fireflyframework-security-resource-server", "fireflyframework-security-oauth2-client", "fireflyframework-security-adapter-opa")) {
            boolean found = false;
            for (int i = 0; i < dependencies.getLength(); i++) {
                var element = (org.w3c.dom.Element) dependencies.item(i);
                if (!element.getElementsByTagName("artifactId").item(0).getTextContent().equals(artifact)) continue;
                assertEquals(0, element.getElementsByTagName("scope").getLength());
                assertEquals(0, element.getElementsByTagName("version").getLength());
                found = true;
            }
            assertTrue(found, "Missing managed production module: " + artifact);
        }
    }

    @Test void productionImportMetadataDiscoversAllRequiredConfigurations() {
        var candidates = org.springframework.boot.context.annotation.ImportCandidates.load(
                org.springframework.boot.autoconfigure.AutoConfiguration.class, getClass().getClassLoader()).getCandidates();
        assertTrue(candidates.containsAll(List.of(LOCAL, RS, OAUTH, NATIVE)));
    }

    @Test void defaultsCreateNativeJwtChainAndStrictDenyPolicyWithoutBank() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SecurityWebFilterChain.class)
                    .hasSingleBean(ReactiveJwtDecoder.class).hasSingleBean(SecurityContextPort.class)
                    .hasSingleBean(PolicyDecisionPort.class).doesNotHaveBean(GlBankOwnerObservationPort.class);
            assertEquals("org.fireflyframework.security.core.policy.EmbeddedPolicyDecisionAdapter", context.getBean(PolicyDecisionPort.class).getClass().getName());
            assertEquals("org.fireflyframework.security.webflux.context.ReactorSecurityContextAdapter", context.getBean(SecurityContextPort.class).getClass().getName());
            assertTrue(context.getEnvironment().getProperty("firefly.security.resource-server.strict-policy", Boolean.class, false));
            assertNull(context.getBean(SecurityContextPort.class).currentPrincipal().block());
            assertFalse(context.getBean(PolicyDecisionPort.class).authorize(null, "GET", "/actuator/health", Map.of()).block().granted());
        });
    }

    @Test void nativeClientAndResourceServerDoNotIntroduceLoginRedirectFilters() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var filters = context.getBean(SecurityWebFilterChain.class).getWebFilters().map(f -> f.getClass().getSimpleName()).collectList().block();
            assertNotNull(filters);
            assertFalse(filters.stream().anyMatch(n -> n.contains("LoginPage") || n.contains("OAuth2AuthorizationRequestRedirect") || n.equals("LogoutWebFilter")));
        });
    }

    @Test void missingRegistrationDoesNotInventAnOAuthClient() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ReactiveClientRegistrationRepository.class)
                    .doesNotHaveBean(ReactiveOAuth2AuthorizedClientManager.class);
        });
    }

    @Test void enabledBankWithoutRegistrationFailsClosed() {
        runner().withPropertyValues(profile(false, true)).run(context -> assertThat(context).hasFailed());
    }

    @Test void enabledBankWithoutPolicyEndpointFailsClosed() {
        runner().withPropertyValues(profile(true, false)).run(context -> assertThat(context).hasFailed());
    }

    @Test void enabledBankUsesRealNativeOAuthJwtContextAndOpaBeans() {
        runner().withPropertyValues(profile(true, true)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GlBankOwnerObservationPort.class)
                    .hasSingleBean(ReactiveOAuth2AuthorizedClientManager.class).hasSingleBean(ReactiveClientRegistrationRepository.class);
            assertInstanceOf(SdkOrganizationBankOwnerObservationAdapter.class, context.getBean(GlBankOwnerObservationPort.class));
            assertEquals("org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager",
                    context.getBean(ReactiveOAuth2AuthorizedClientManager.class).getClass().getName());
            assertEquals("org.fireflyframework.security.adapter.opa.OpaPolicyDecisionAdapter", context.getBean(PolicyDecisionPort.class).getClass().getName());
            var registration = context.getBean(ReactiveClientRegistrationRepository.class).findByRegistrationId("gl-bank-owner").block();
            assertNotNull(registration);
            assertEquals("client_credentials", registration.getAuthorizationGrantType().getValue());
            assertEquals("https://issuer.example.invalid/organization/oauth/token", registration.getProviderDetails().getTokenUri());
        });
    }

    @Test void enabledBankRejectsNonStrictPolicy() {
        runner().withPropertyValues(profile(true, true)).withPropertyValues("firefly.security.resource-server.strict-policy=false")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void operatorProvidedDenyChainRemainsSelected() {
        runner().withUserConfiguration(OperatorDenyChain.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SecurityWebFilterChain.class);
            assertSame(context.getBean("operatorDenyChain"), context.getBean(SecurityWebFilterChain.class));
            assertFalse(context.containsBean("fireflySecurityWebFilterChain"));
        });
    }

    @Test void existingRealPolicyBackendRemainsSelectedWithoutLocalProperties() {
        runner().withUserConfiguration(ExistingPolicyBackend.class).withPropertyValues(profile(true, false)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PolicyDecisionPort.class);
            assertSame(context.getBean("existingPolicyBackend"), context.getBean(PolicyDecisionPort.class));
            assertFalse(context.containsBean("glBankOwnerPolicyDecisionPort"));
        });
    }

    @Test void policyPropertiesRequireAValidBoundedEndpoint() throws Exception {
        var constructor = required("io.fireflysoftware.core.accounting.web.config.posting.GlBankOwnerPolicyProperties").getConstructor(URI.class, String.class);
        assertDoesNotThrow(() -> constructor.newInstance(URI.create("https://policy.example.invalid"), "firefly/accounting/allow"));
        assertDoesNotThrow(() -> constructor.newInstance(URI.create("http://127.0.0.1:8181"), "firefly/accounting/allow"));
        for (String uri : List.of("http://policy.example.invalid", "https://user:password@policy.example.invalid", "https://policy.example.invalid?token=x", "https://policy.example.invalid/a"))
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> constructor.newInstance(URI.create(uri), "firefly/accounting/allow"));
        for (String path : List.of("", "../allow", "allow?x=y", "allow\n"))
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> constructor.newInstance(URI.create("https://policy.example.invalid"), path));
    }

    @Test void optInResourceUsesStandardClientCredentialsAndRequiredSecret() throws Exception {
        Path profile = ROOT.resolve(WEB + "/src/main/resources/application-bank-owner-observation.yaml");
        assertTrue(java.nio.file.Files.isRegularFile(profile));
        String text = java.nio.file.Files.readString(profile);
        assertTrue(text.contains("authorization-grant-type: client_credentials"));
        assertTrue(text.contains("client-secret: ${GL_OWNER_CLIENT_SECRET}"));
        assertFalse(text.contains("permit-matchers"));
        assertFalse(text.contains("client-secret: ${GL_OWNER_CLIENT_SECRET:"));
    }

    ReactiveWebApplicationContextRunner actualProfile(boolean secret) {
        var values = new HashMap<String,Object>();
        values.putAll(Map.of("GL_AUTH_ISSUER", "https://issuer.example.invalid/accounting",
                "GL_AUTH_JWKS_URI", "https://issuer.example.invalid/accounting/jwks",
                "GL_AUTH_AUDIENCE", "accounting-observation",
                "GL_BANK_OWNER_POLICY_BASE_URI", "https://policy.example.invalid",
                "GL_BANK_OWNER_POLICY_DECISION_PATH", "firefly/accounting/allow"));
        for (String name : List.of("TENANT_ID", "BANK_ID", "ENROLLMENT_ID", "INSTITUTION_ID", "ISSUER_AUTHORITY_ID"))
            values.put("GL_OWNER_" + name, "00000000-0000-0000-0000-000000000001");
        values.put("GL_OWNER_PLATFORM_TENANT_ID", "00000000-0000-0000-0000-000000000002");
        values.put("GL_OWNER_ENROLLMENT_VERSION", "1");
        values.put("GL_OWNER_BASE_URI", "https://organization.example.invalid");
        values.put("GL_OWNER_TOKEN_URI", "https://issuer.example.invalid/organization/oauth/token");
        values.put("GL_OWNER_JWKS_URI", "https://issuer.example.invalid/organization/jwks");
        values.put("GL_OWNER_ISSUER", "https://issuer.example.invalid/organization");
        values.put("GL_OWNER_AUDIENCE", "organization-observation");
        values.put("GL_OWNER_SUBJECT", "gl-bank-owner-observer");
        values.put("GL_OWNER_CLIENT_ID", "gl-bank-owner");
        if (secret) values.put("GL_OWNER_CLIENT_SECRET", UUID.randomUUID().toString());
        return runner().withInitializer(context -> {
            try {
                var sources = new YamlPropertySourceLoader().load("actual-gl-bank-profile",
                        new FileSystemResource(ROOT.resolve(WEB + "/src/main/resources/application-bank-owner-observation.yaml")));
                context.getEnvironment().getPropertySources().addFirst(sources.getFirst());
                context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("controlled-configuration", values));
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        });
    }

    @Test void actualProfileYamlBindsNativeOAuthTenantAndProductionBankPort() {
        actualProfile(true).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GlBankOwnerObservationPort.class)
                    .hasSingleBean(ReactiveOAuth2AuthorizedClientManager.class);
            Object properties = context.getBean(required("org.fireflyframework.security.rs.ResourceServerProperties"));
            assertEquals(List.of("tenant_id"), assertDoesNotThrow(() -> properties.getClass().getMethod("getTenantClaimPaths").invoke(properties)));
            assertEquals(Boolean.TRUE, assertDoesNotThrow(() -> properties.getClass().getMethod("isTenantRequired").invoke(properties)));
        });
    }

    @Test void actualProfileWithoutSecretFailsClosed() {
        actualProfile(false).run(context -> assertThat(context).hasFailed());
    }

    @Test void blankNativeSecretCannotCreateTheOptInApplication() {
        runner().withPropertyValues(profile(true, true))
                .withPropertyValues("spring.security.oauth2.client.registration.gl-bank-owner.client-secret=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void unresolvedNativeSecretFailsWithoutIncludingItsValue() {
        runner().withPropertyValues(profile(true, true))
                .withPropertyValues("spring.security.oauth2.client.registration.gl-bank-owner.client-secret=${UNSET_QUALIFIED_GL_CLIENT_SECRET}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    var failure = context.getStartupFailure();
                    boolean credentialFailure = false;
                    for (int i = 0; failure != null && i < 12; i++, failure = failure.getCause()) {
                        String message = String.valueOf(failure.getMessage());
                        assertFalse(message.contains("UNSET_QUALIFIED_GL_CLIENT_SECRET"));
                        credentialFailure |= message.contains("Explicit matching Bank owner OAuth client credentials required");
                    }
                    assertTrue(credentialFailure);
                });
    }

    @Test void realOperatorRegistrationAndManagerRemainSelectedWithoutNativeProperties() {
        runner().withUserConfiguration(OperatorClients.class).withPropertyValues(profile(false, true)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GlBankOwnerObservationPort.class)
                    .hasSingleBean(ReactiveClientRegistrationRepository.class).hasSingleBean(ReactiveOAuth2AuthorizedClientManager.class);
            assertSame(context.getBean("operatorRegistrations"), context.getBean(ReactiveClientRegistrationRepository.class));
            assertSame(context.getBean("operatorClients"), context.getBean(ReactiveOAuth2AuthorizedClientManager.class));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class OperatorDenyChain {
        @Bean SecurityWebFilterChain operatorDenyChain(ServerHttpSecurity http) {
            return http.authorizeExchange(rules -> rules.anyExchange().denyAll()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExistingPolicyBackend {
        @Bean PolicyDecisionPort existingPolicyBackend() {
            try {
                return (PolicyDecisionPort) required("org.fireflyframework.security.adapter.opa.OpaPolicyDecisionAdapter")
                        .getConstructor(WebClient.class, String.class).newInstance(WebClient.builder().baseUrl("https://policy.example.invalid").build(), "firefly/accounting/allow");
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OperatorClients {
        @Bean ReactiveClientRegistrationRepository operatorRegistrations() {
            return new InMemoryReactiveClientRegistrationRepository(ClientRegistration.withRegistrationId("gl-bank-owner")
                    .clientId("gl-bank-owner").clientSecret(UUID.randomUUID().toString())
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                    .tokenUri("https://issuer.example.invalid/organization/oauth/token").build());
        }
        @Bean ReactiveOAuth2AuthorizedClientManager operatorClients(ReactiveClientRegistrationRepository registrations) {
            return new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registrations,
                    new InMemoryReactiveOAuth2AuthorizedClientService(registrations));
        }
    }
}
