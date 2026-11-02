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
import java.util.concurrent.atomic.AtomicInteger;

/** Owned RS256/JWKS and explicit legacy-only policy transport; never grants native financial authority. */
public final class GlLegacySignedAuthorityFixtureImpl implements AutoCloseable {
    public static final String SUBJECT = "synthetic-gl-legacy-operator";
    public static final String AUDIENCE = "synthetic-gl-legacy";
    public static final String SCOPE = "gl:legacy:fixture";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private final String tenant = UUID.randomUUID().toString(), staff = UUID.randomUUID().toString(), kid = UUID.randomUUID().toString();
    private final KeyPair key;
    private final HttpServer server;
    private final AtomicInteger jwksRequests = new AtomicInteger(), policyRequests = new AtomicInteger(), permits = new AtomicInteger(), refusals = new AtomicInteger();

    public GlLegacySignedAuthorityFixtureImpl() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var publicKey = (RSAPublicKey) key.getPublic();
        byte[] jwks = JSON.writeValueAsBytes(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", kid, "alg", "RS256", "use", "sig",
                "n", unsigned(publicKey.getModulus()), "e", unsigned(publicKey.getPublicExponent())))));
        server.createContext("/jwks", exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod()) || !"/jwks".equals(exchange.getRequestURI().getPath())) {
                    exchange.sendResponseHeaders(404, -1);return;
                }
                jwksRequests.incrementAndGet();exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);exchange.getResponseBody().write(jwks);
            }
        });
        server.createContext("/v1/data/gl-legacy/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if (!"POST".equals(exchange.getRequestMethod()) || !Set.of("/v1/data/gl-legacy/allow", "/v1/data/gl-legacy/current").contains(path)) {
                    exchange.sendResponseHeaders(404, -1);return;
                }
                byte[] body = exchange.getRequestBody().readNBytes(65537);
                if (body.length > 65536) {exchange.sendResponseHeaders(413, -1);return;}
                var input = JSON.readTree(body).path("input");var subject = input.path("subject");
                String action = input.path("action").asText(), resource = input.path("resource").asText();
                boolean allowed = issuer().equals(subject.path("issuer").asText()) && SUBJECT.equals(subject.path("subject").asText())
                        && tenant.equals(subject.path("tenantId").asText()) && subject.path("scopes").isArray()
                        && java.util.stream.StreamSupport.stream(subject.path("scopes").spliterator(), false).anyMatch(value -> SCOPE.equals(value.asText()))
                        && action.equals(input.path("context").path("method").asText())
                        && resource.equals(input.path("context").path("path").asText())
                        && (action.equals("GET") && resource.equals("/v3/api-docs")
                        || Set.of("GET", "POST", "PUT", "DELETE").contains(action)
                        && resource.matches("/api/v1/(?:journal-batches|gl-accounts)(?:/.*)?"));
                policyRequests.incrementAndGet();if (allowed) permits.incrementAndGet();else refusals.incrementAndGet();
                byte[] response = JSON.writeValueAsBytes(path.endsWith("/current")
                        ? Map.of("result", Map.of("allow", allowed, "policy_revision", "synthetic-gl-legacy-r1"), "decision_id", UUID.randomUUID().toString())
                        : Map.of("result", allowed));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);exchange.getResponseBody().write(response);
            }
        });
        server.start();
    }

    public String baseUrl() {return "http://127.0.0.1:" + server.getAddress().getPort();}
    public String issuer() {return baseUrl() + "/issuer";}
    public String tenant() {return tenant;}
    public String token() throws Exception {return token(issuer(), AUDIENCE, tenant, SCOPE);}
    public String token(String issuer, String audience, String tenant, String scope) throws Exception {
        long now = Instant.now().getEpochSecond();
        byte[] header = JSON.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT", "kid", kid));
        var claims = new HashMap<String, Object>(Map.of("iss", issuer, "sub", SUBJECT, "aud", List.of(audience), "tenant", tenant,
                "scope", scope, "iat", now, "nbf", now - 1, "exp", now + 240, "principal_kind", "STAFF", "staff_actor_id", staff));
        claims.put("azp", "synthetic-gl-legacy");byte[] body = JSON.writeValueAsBytes(claims);
        String content = BASE64.encodeToString(header) + "." + BASE64.encodeToString(body);
        var signer = Signature.getInstance("SHA256withRSA");signer.initSign(key.getPrivate());signer.update(content.getBytes(StandardCharsets.US_ASCII));
        return content + "." + BASE64.encodeToString(signer.sign());
    }
    public String invalidSignature() throws Exception {
        String[] parts = token().split("\\.");byte[] signature = Base64.getUrlDecoder().decode(parts[2]);signature[0] ^= 1;
        return parts[0] + "." + parts[1] + "." + BASE64.encodeToString(signature);
    }
    public Map<String, Object> proof() {
        return Map.of("schema", "GL_LEGACY_SIGNED_AUTHORITY_V1", "expectedIssuer", issuer(), "expectedSubject", SUBJECT,
                "expectedTenant", tenant, "requiredScope", SCOPE, "jwksRequests", jwksRequests.get(), "policyRequests", policyRequests.get(),
                "policyPermits", permits.get(), "policyRefusals", refusals.get());
    }
    private static String unsigned(java.math.BigInteger value) {
        byte[] bytes = value.toByteArray();return BASE64.encodeToString(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }
    @Override public void close() {server.stop(0);}
    @Override public String toString() {return "GlLegacySignedAuthorityFixtureImpl[redacted]";}
}
