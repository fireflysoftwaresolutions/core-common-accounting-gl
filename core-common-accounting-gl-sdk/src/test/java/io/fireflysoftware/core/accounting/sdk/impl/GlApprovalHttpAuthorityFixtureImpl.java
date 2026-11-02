/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.sdk.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Controlled signed staff and current PDP transport for the original production chain. */
public final class GlApprovalHttpAuthorityFixtureImpl implements AutoCloseable {
    public static final String AUDIENCE="synthetic-gl-financial-approval",SCOPE="gl:approval:fixture";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Base64.Encoder BASE64=Base64.getUrlEncoder().withoutPadding();
    private final String tenant,book,platform,kid=UUID.randomUUID().toString();
    private final KeyPair key;
    private final HttpServer server;
    private final AtomicInteger jwksRequests=new AtomicInteger(),policyPermits=new AtomicInteger(),policyRefusals=new AtomicInteger();
    private final AtomicBoolean current=new AtomicBoolean(true);
    private static final List<String> PREDICATES=List.of("issuerMatch","tenantMatch","subjectAllowed","scopePresent","actionMatches","pathMatches","httpAllowed","ownerAllowed","current");
    private final AtomicIntegerArray predicateTrueCounts=new AtomicIntegerArray(PREDICATES.size());
    private final AtomicReference<Map<String,Boolean>> lastPredicates=new AtomicReference<>(Map.of());

    public GlApprovalHttpAuthorityFixtureImpl(UUID tenant,UUID book,UUID platform)throws Exception {
        this.tenant=tenant.toString();this.book=book.toString();this.platform=platform.toString();
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=generator.generateKeyPair();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var publicKey=(RSAPublicKey)key.getPublic();
        var jwks=JSON.writeValueAsBytes(Map.of("keys",List.of(Map.of("kty","RSA","kid",kid,"alg","RS256","use","sig",
                "n",unsigned(publicKey.getModulus()),"e",unsigned(publicKey.getPublicExponent())))));
        server.createContext("/jwks",exchange->{try(exchange){
            if(!"GET".equals(exchange.getRequestMethod())||!"/jwks".equals(exchange.getRequestURI().getPath())){exchange.sendResponseHeaders(404,-1);return;}
            jwksRequests.incrementAndGet();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,jwks.length);exchange.getResponseBody().write(jwks);
        }});
        server.createContext("/v1/data/gl-approval/",exchange->{try(exchange){
            var path=exchange.getRequestURI().getPath();
            if(!"POST".equals(exchange.getRequestMethod())||!Set.of("/v1/data/gl-approval/allow","/v1/data/gl-approval/current").contains(path)){
                exchange.sendResponseHeaders(404,-1);return;
            }
            var bytes=exchange.getRequestBody().readNBytes(1_048_577);if(bytes.length>1_048_576){exchange.sendResponseHeaders(413,-1);return;}
            var input=JSON.readTree(bytes).path("input");var actor=input.path("subject");var context=input.path("context");
            String action=input.path("action").asText(),resource=input.path("resource").asText();
            boolean identity=issuer().equals(actor.path("issuer").asText())&&this.platform.equals(actor.path("tenantId").asText())
                    &&Set.of("maker","checker").contains(actor.path("subject").asText())&&actor.path("scopes").isArray()
                    &&java.util.stream.StreamSupport.stream(actor.path("scopes").spliterator(),false).anyMatch(value->SCOPE.equals(value.asText()));
            String root="/api/v1/tenants/"+tenant+"/books/"+book+"/financial-approvals/";
            boolean http=action.equals(context.path("method").asText())&&resource.equals(context.path("path").asText())
                    &&(action.equals("GET")&&resource.equals("/v3/api-docs")||Set.of("GET","POST").contains(action)
                    &&resource.matches(java.util.regex.Pattern.quote(root)+"[0-9a-f-]{36}(?:/(?:check|revoke))?"));
            boolean owner=this.tenant.equals(context.path("tenantId").asText())&&this.book.equals(context.path("bookId").asText())
                    &&(action.equals("accounting.gl.read")||action.startsWith("accounting.gl.approval."))
                    &&(resource.equals("accounting/book/"+book)||resource.matches("accounting/account/[0-9a-f-]{36}"));
            boolean currentPolicy=current.get();
            recordPredicates(issuer().equals(actor.path("issuer").asText()),this.platform.equals(actor.path("tenantId").asText()),
                    Set.of("maker","checker").contains(actor.path("subject").asText()),actor.path("scopes").isArray()
                    &&java.util.stream.StreamSupport.stream(actor.path("scopes").spliterator(),false).anyMatch(value->SCOPE.equals(value.asText())),
                    action.equals(context.path("method").asText()),resource.equals(context.path("path").asText()),http,owner,currentPolicy);
            boolean allowed=currentPolicy&&identity&&(http||owner);if(allowed)policyPermits.incrementAndGet();else policyRefusals.incrementAndGet();
            byte[] response=JSON.writeValueAsBytes(path.endsWith("/current")?Map.of("result",Map.of("allow",allowed,"policy_revision","synthetic-gl-financial-http-r1"),
                    "decision_id",UUID.randomUUID().toString()):Map.of("result",allowed));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }});
        server.start();
    }
    public String baseUrl(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    public String issuer(){return baseUrl()+"/issuer";}
    public void current(boolean value){current.set(value);}
    public String token(String subject,UUID staff)throws Exception{return token(subject,staff,issuer(),AUDIENCE,platform,SCOPE);}
    public String token(String subject,UUID staff,String issuer,String audience,String platform,String scope)throws Exception {
        long now=Instant.now().getEpochSecond();var header=JSON.writeValueAsBytes(Map.of("alg","RS256","typ","JWT","kid",kid));
        var claims=new HashMap<String,Object>(Map.of("iss",issuer,"sub",subject,"aud",List.of(audience),"tenant",platform,"scope",scope,"iat",now,"nbf",now-1,"exp",now+120,
                "principal_kind","STAFF","staff_actor_id",staff.toString()));claims.put("azp","synthetic-financial-http");
        String content=BASE64.encodeToString(header)+"."+BASE64.encodeToString(JSON.writeValueAsBytes(claims));
        var signer=Signature.getInstance("SHA256withRSA");signer.initSign(key.getPrivate());signer.update(content.getBytes(StandardCharsets.US_ASCII));
        return content+"."+BASE64.encodeToString(signer.sign());
    }
    public String invalidSignature(String subject,UUID staff)throws Exception {
        var parts=token(subject,staff).split("\\.");var signature=Base64.getUrlDecoder().decode(parts[2]);signature[0]^=1;
        return parts[0]+"."+parts[1]+"."+BASE64.encodeToString(signature);
    }
    public Map<String,Object> proof(){return Map.of("schema","GL_APPROVAL_HTTP_AUTHORITY_V1","issuer",issuer(),"audience",AUDIENCE,
            "platformTenant",platform,"resourceTenant",tenant,"book",book,"jwksRequests",jwksRequests.get(),"policyPermits",policyPermits.get(),"policyRefusals",policyRefusals.get(),"policyPredicateDiagnostics",predicateDiagnostics());}
    private void recordPredicates(boolean... matches){
        if(matches.length!=PREDICATES.size())throw new IllegalArgumentException("Fixed predicate diagnostic shape required");
        var last=new LinkedHashMap<String,Boolean>();for(int i=0;i<matches.length;i++){last.put(PREDICATES.get(i),matches[i]);if(matches[i])predicateTrueCounts.incrementAndGet(i);}lastPredicates.set(Map.copyOf(last));
    }
    private Map<String,Object> predicateDiagnostics(){var counts=new LinkedHashMap<String,Integer>();for(int i=0;i<PREDICATES.size();i++)counts.put(PREDICATES.get(i),predicateTrueCounts.get(i));return Map.of("trueCounts",Map.copyOf(counts),"last",lastPredicates.get());}
    private static String unsigned(java.math.BigInteger value){byte[] bytes=value.toByteArray();return BASE64.encodeToString(bytes[0]==0?Arrays.copyOfRange(bytes,1,bytes.length):bytes);}
    @Override public void close(){server.stop(0);}
    @Override public String toString(){return "GlApprovalHttpAuthorityFixtureImpl[redacted]";}
}
