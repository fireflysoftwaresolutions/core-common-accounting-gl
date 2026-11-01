/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.sdk.impl;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import org.fireflyframework.security.adapter.opa.*;
import org.fireflyframework.security.core.key.InMemoryKeyManagementAdapter;
import org.fireflyframework.security.rs.*;
import org.fireflyframework.security.webflux.authentication.PrincipalSupport;
import org.fireflyframework.security.webflux.authz.PolicyAuthorizationManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Genuine released framework projection/OPA HTTP; not Boot Main readiness or a policy allow fallback. */
class GlApprovalHttpAuthorityFixtureWireTest {
    private static final UUID TENANT=new UUID(0,1),BOOK=new UUID(0,2),PLATFORM=new UUID(0,3);
    private Map<String,Object> actualFrameworkOrigins=Map.of();
    private void verifyActualFrameworkOrigins()throws Exception{
        var pins=Map.<Class<?>,String>of(OpaPolicyDecisionAdapter.class,"d5f68adcc93b2ef8404052e6a00dd6a8b6a6debc4f07113834ec2476a81f68d4",OpaPolicyEvaluationAdapter.class,"d5f68adcc93b2ef8404052e6a00dd6a8b6a6debc4f07113834ec2476a81f68d4",ResourceServerAutoConfiguration.class,"d4419571b5c7ff9fca7b695f2be9bac634c5201b176cef27b6c5d213147bf96f",org.fireflyframework.security.core.context.PrincipalFactory.class,"5cdef13b64faf389ddcb07236a1041bc907707d09a41bd429d6ded7bcce6c799",PrincipalSupport.class,"fcdef23c76866fd929e46da1a80376f9d418b563b4013fd55321a6ac0c8a57f1");
        var rows=new TreeMap<String,Object>();
        for(var entry:pins.entrySet()){
            var type=entry.getKey();var path=java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();assertTrue(java.nio.file.Files.isRegularFile(path)&&java.nio.file.Files.size(path)<=32*1024*1024,"Bounded actual framework JAR provider required");assertTrue(path.getFileName().toString().endsWith("-26.10.23.jar"));
            var resource=type.getName().replace('.','/')+".class";assertEquals(1,Collections.list(type.getClassLoader().getResources(resource)).size(),"Sole actual framework class provider required");var jarSha=sha(java.nio.file.Files.readAllBytes(path));assertEquals(entry.getValue(),jarSha,"Actual provider must match independently read immutable Central26.10.23 bytes");
            try(var jar=new java.util.jar.JarFile(path.toFile());var input=type.getClassLoader().getResourceAsStream(resource);var expected=jar.getInputStream(jar.getJarEntry(resource))){var actual=input.readAllBytes();assertArrayEquals(expected.readAllBytes(),actual);rows.put(type.getSimpleName(),Map.of("providerJar",path.getFileName().toString(),"jarSha256",jarSha,"classSha256",sha(actual),"soleProvider",true));}
        }
        actualFrameworkOrigins=Map.copyOf(rows);
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private String diagnostics(GlApprovalHttpAuthorityFixtureImpl fixture,org.springframework.security.core.Authentication auth){var actor=PrincipalSupport.extract(auth);var projection=Map.of("issuerMatch",fixture.issuer().equals(actor.issuer()),"tenantMatch",PLATFORM.toString().equals(actor.tenantId()),"subjectAllowed",Set.of("maker","checker").contains(actor.subject()),"scopePresent",actor.hasScope(GlApprovalHttpAuthorityFixtureImpl.SCOPE));return Map.of("fixedWirePredicates",last(fixture),"fixedNativeProjection",projection,"actualFrameworkOrigins",actualFrameworkOrigins).toString();}
    private org.springframework.security.authentication.AbstractAuthenticationToken authentication(GlApprovalHttpAuthorityFixtureImpl fixture)throws Exception{return authentication(fixture,fixture.token("maker",new UUID(0,4)));}
    private org.springframework.security.authentication.AbstractAuthenticationToken authentication(GlApprovalHttpAuthorityFixtureImpl fixture,String token)throws Exception{
        verifyActualFrameworkOrigins();
        var properties=new ResourceServerProperties();properties.setIssuer(fixture.issuer());properties.setJwkSetUri(fixture.baseUrl()+"/jwks");properties.setAudiences(List.of(GlApprovalHttpAuthorityFixtureImpl.AUDIENCE));properties.setTenantRequired(true);properties.setTenantClaimPaths(List.of("tenant"));properties.setScopeClaimPaths(List.of("scope"));
        var configuration=new ResourceServerAutoConfiguration();var principal=configuration.fireflyPrincipalFactory(configuration.fireflyAuthorityMappingPort(properties));var decoder=configuration.fireflyReactiveJwtDecoder(new InMemoryKeyManagementAdapter(),properties);var jwt=decoder.decode(token).block(Duration.ofSeconds(5));return configuration.fireflyJwtAuthenticationConverter(principal,properties).convert(jwt).block(Duration.ofSeconds(5));
    }
    private Map<?,?> last(GlApprovalHttpAuthorityFixtureImpl fixture){return (Map<?,?>)((Map<?,?>)fixture.proof().get("policyPredicateDiagnostics")).get("last");}
    @Test void actualSignedProjectionAndGenuineOpaReadinessTupleMatch()throws Exception{
        try(var fixture=new GlApprovalHttpAuthorityFixtureImpl(TENANT,BOOK,PLATFORM)){var auth=authentication(fixture);var policies=new OpaPolicyDecisionAdapter(WebClient.builder().baseUrl(fixture.baseUrl()).build(),"gl-approval/allow");var manager=new PolicyAuthorizationManager(policies);var decision=manager.authorize(Mono.just(auth),new AuthorizationContext(MockServerWebExchange.from(MockServerHttpRequest.get("/v3/api-docs")))).block(Duration.ofSeconds(5));assertTrue(decision.isGranted(),()->diagnostics(fixture,auth));for(var key:List.of("issuerMatch","tenantMatch","subjectAllowed","scopePresent","actionMatches","pathMatches","httpAllowed","current"))assertEquals(true,last(fixture).get(key));assertEquals(false,last(fixture).get("ownerAllowed"));}
    }
    @Test void wrongHttpContextStillDeniesWithoutRecordingRawValues()throws Exception{
        try(var fixture=new GlApprovalHttpAuthorityFixtureImpl(TENANT,BOOK,PLATFORM)){var actor=PrincipalSupport.extract(authentication(fixture));var policies=new OpaPolicyDecisionAdapter(WebClient.builder().baseUrl(fixture.baseUrl()).build(),"gl-approval/allow");assertFalse(policies.authorize(actor,"GET","/v3/api-docs",Map.of("method","POST","path","/v3/api-docs")).block(Duration.ofSeconds(5)).granted());assertEquals(false,last(fixture).get("actionMatches"));assertEquals(false,last(fixture).get("httpAllowed"));assertEquals(9,last(fixture).size());assertTrue(last(fixture).values().stream().allMatch(Boolean.class::isInstance));var wrongTenant=PrincipalSupport.extract(authentication(fixture,fixture.token("maker",new UUID(0,4),fixture.issuer(),GlApprovalHttpAuthorityFixtureImpl.AUDIENCE,new UUID(0,99).toString(),GlApprovalHttpAuthorityFixtureImpl.SCOPE)));assertFalse(policies.authorize(wrongTenant,"GET","/v3/api-docs",Map.of("method","GET","path","/v3/api-docs")).block(Duration.ofSeconds(5)).granted());assertEquals(false,last(fixture).get("tenantMatch"));assertEquals(true,last(fixture).get("httpAllowed"));}
    }
    @Test void currentOwnerDecisionRemainsSeparateAndCannotFallbackAfterWithdrawal()throws Exception{
        try(var fixture=new GlApprovalHttpAuthorityFixtureImpl(TENANT,BOOK,PLATFORM)){var auth=authentication(fixture);var actor=PrincipalSupport.extract(auth);var policies=new OpaPolicyEvaluationAdapter(WebClient.builder().baseUrl(fixture.baseUrl()).build(),"gl-approval/current");var context=Map.<String,Object>of("tenantId",TENANT.toString(),"bookId",BOOK.toString());assertTrue(policies.evaluate(actor,"accounting.gl.read","accounting/book/"+BOOK,context).block(Duration.ofSeconds(5)).decision().granted(),()->diagnostics(fixture,auth));assertEquals(true,last(fixture).get("ownerAllowed"));assertEquals(false,last(fixture).get("httpAllowed"));assertFalse(policies.evaluate(actor,"accounting.gl.read","accounting/book/"+BOOK,Map.of("tenantId",new UUID(0,99).toString(),"bookId",BOOK.toString())).block(Duration.ofSeconds(5)).decision().granted());assertEquals(false,last(fixture).get("ownerAllowed"));fixture.current(false);assertFalse(policies.evaluate(actor,"accounting.gl.read","accounting/book/"+BOOK,context).block(Duration.ofSeconds(5)).decision().granted());assertEquals(false,last(fixture).get("current"));}
    }
}
