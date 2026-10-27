/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.core.services.posting;

import reactor.core.publisher.Mono;

/** Finite Bank-profile evidence only; never an accounting approval or posting permission. */
public interface GlBankOwnerObservationPort {
    Mono<GlBankOwnerObservation> observe(GlBankOwnerExpectation expected);
}
