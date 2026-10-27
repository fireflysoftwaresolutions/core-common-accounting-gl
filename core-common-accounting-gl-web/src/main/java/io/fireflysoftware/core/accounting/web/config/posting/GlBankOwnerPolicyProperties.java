/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.web.config.posting;

import java.net.URI;
import java.util.Set;

public record GlBankOwnerPolicyProperties(URI baseUri, String decisionPath) {
    public GlBankOwnerPolicyProperties {
        if (baseUri == null || baseUri.toString().length() > 2048 || baseUri.getHost() == null
                || !("https".equals(baseUri.getScheme()) || "http".equals(baseUri.getScheme())
                    && Set.of("127.0.0.1", "::1", "[::1]").contains(baseUri.getHost()))
                || baseUri.getUserInfo() != null || baseUri.getRawQuery() != null || baseUri.getRawFragment() != null
                || !(baseUri.getPath().isEmpty() || baseUri.getPath().equals("/"))
                || decisionPath == null || decisionPath.length() > 512
                || !decisionPath.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))
            throw new IllegalArgumentException("Explicit valid Bank owner policy endpoint and decision path required");
    }
}
