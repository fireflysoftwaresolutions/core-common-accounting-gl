/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting.ledger;

import io.fireflysoftware.core.accounting.core.services.posting.GlPostingRules;
import java.net.URI;
import java.util.*;

/** An explicit resource-tenant registration and independently validated outgoing machine identity. */
public record GlLedgerObserverProfile(String resourceTenantId, String registrationId, URI baseUri, URI tokenUri, URI jwksUri,
        String issuer, String audience, String subject, String clientId, String tenantClaim, String clientClaim,
        String kindClaim, String machineValue, String staffClaim, String scopeClaim, String accountsClaim, boolean allowLoopbackHttp) {
    public GlLedgerObserverProfile {
        GlPostingRules.uuid(resourceTenantId);
        for (String value : List.of(registrationId, issuer, audience, subject, clientId, tenantClaim, clientClaim,
                kindClaim, machineValue, staffClaim, scopeClaim, accountsClaim)) GlPostingRules.text(value, 500);
        if (Set.of(tenantClaim, clientClaim, kindClaim, staffClaim, scopeClaim, accountsClaim).size() != 6)
            throw new IllegalArgumentException("Distinct signed Ledger claim names required");
        for (URI uri : List.of(baseUri, tokenUri, jwksUri, URI.create(issuer))) {
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || allowLoopbackHttp && "http".equals(uri.getScheme())
                    && Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(uri.getHost())))
                throw new IllegalArgumentException("Explicit secure Ledger observer endpoints required");
        }
        if (!baseUri.getPath().isEmpty() && !baseUri.getPath().equals("/"))
            throw new IllegalArgumentException("Ledger owner root origin required");
    }
}
