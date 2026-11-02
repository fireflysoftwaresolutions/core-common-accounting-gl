package io.fireflysoftware.core.accounting.web.config.posting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import io.fireflysoftware.core.accounting.core.services.posting.GlBankOwnerObservationPort;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

class GlProductionPropertiesScanTest {
    @Configuration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan(basePackageClasses = GlBankOwnerPolicyProperties.class)
    static class ActualWebPropertiesScan {}

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = GlBankOwnerPolicyProperties.class, excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test(?:\\$.*)?")
    })
    @ConfigurationPropertiesScan(basePackageClasses = GlBankOwnerPolicyProperties.class)
    static class ActualWebPackageScan {}

    private void requireActualProductionScanScope() throws Exception {
        String main = Files.readString(GlBankOwnerProductionSecurityTest.ROOT.resolve(
                "core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/GeneralLedgerApplication.java"));
        assertTrue(main.contains("@ConfigurationPropertiesScan"));
        assertTrue(main.contains("\"io.fireflysoftware.core.accounting\""));
        assertTrue(GlBankOwnerPolicyProperties.class.getPackageName().startsWith("io.fireflysoftware.core.accounting.web"));
    }

    @Test void actualProductionPackageScanKeepsDisabledBankAndOpaDormant() throws Exception {
        requireActualProductionScanScope();
        new GlBankOwnerProductionSecurityTest().runner().withUserConfiguration(ActualWebPackageScan.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(GlBankOwnerObservationPort.class);
                    assertFalse(context.containsBean("glBankOwnerPolicyDecisionPort"));
                    assertFalse(context.containsBean("operatorPolicyBackend"));
                    assertEquals("org.fireflyframework.security.core.policy.EmbeddedPolicyDecisionAdapter",
                            context.getBean(PolicyDecisionPort.class).getClass().getName());
                });
    }

    @Test void actualProductionPackageScanDiscoversExistingOperatorPolicyBackend() throws Exception {
        requireActualProductionScanScope();
        var fixture = new GlBankOwnerProductionSecurityTest();
        fixture.runner().withUserConfiguration(ActualWebPackageScan.class)
                .withPropertyValues(fixture.profile(true, false))
                .withPropertyValues("qualified.operator-policy.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PolicyDecisionPort.class);
                    assertSame(context.getBean("operatorPolicyBackend"), context.getBean(PolicyDecisionPort.class));
                    assertFalse(context.containsBean("glBankOwnerPolicyDecisionPort"));
                });
    }

    @Test void productionWebPropertiesScanMustKeepDisabledBankOptional() {
        new GlBankOwnerProductionSecurityTest().runner()
                .withUserConfiguration(ActualWebPropertiesScan.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test void productionWebPropertiesScanMustPreserveExistingPolicyBackendBackoff() {
        var fixture = new GlBankOwnerProductionSecurityTest();
        fixture.runner().withUserConfiguration(ActualWebPropertiesScan.class,
                GlBankOwnerProductionSecurityTest.ExistingPolicyBackend.class)
                .withPropertyValues(fixture.profile(true, false))
                .run(context -> assertThat(context).hasNotFailed());
    }
}
