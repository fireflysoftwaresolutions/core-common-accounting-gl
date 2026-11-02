/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

/** Explicit organization issuer policy; claim names are not generic principal conventions. */
public record GlBankOwnerObserverProfile(GlBankOwnerScope scope, String registrationId,
        String platformTenantId, URI baseUri, URI tokenUri, URI jwksUri, String issuer,
        String audience, String subject, String clientId, String tenantClaim, String kindClaim,
        String machineValue, String clientClaim, String staffClaim) {
    public GlBankOwnerObserverProfile {
        Objects.requireNonNull(scope);
        GlPostingRules.uuid(platformTenantId);
        for (String text : new String[]{registrationId, issuer, audience, subject, clientId,
                tenantClaim, kindClaim, machineValue, clientClaim, staffClaim})
            if (text == null || text.isBlank() || text.length() > 500 || text.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Complete organization observer profile required");
        if (Set.of(tenantClaim, kindClaim, clientClaim, staffClaim).size() != 4)
            throw new IllegalArgumentException("Distinct organization claim paths required");
        for (URI uri : new URI[]{baseUri, tokenUri, jwksUri, URI.create(issuer)})
            if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("HTTPS observer endpoints required");
        if (!baseUri.getPath().isEmpty() && !baseUri.getPath().equals("/"))
            throw new IllegalArgumentException("Organization root endpoint required");
    }
}
