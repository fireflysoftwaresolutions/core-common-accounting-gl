/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.core.accounting.core.services.posting;

import java.time.Duration;
import java.util.function.LongSupplier;

public final class GlNativeOperation {
    private final GlAccountingAuthority.Admission admission;
    private final GlNativeTimeWindow window;
    private final LongSupplier nanos, epoch;

    GlNativeOperation(
            GlAccountingAuthority.Admission original,
            GlNativeTimeWindow window,
            LongSupplier nanos,
            LongSupplier epoch) {
        this.window = window;
        this.nanos = nanos;
        this.epoch = epoch;
        long until =
                Math.min(
                        original.validUntilEpochMillis(),
                        window.untilAt(nanos.getAsLong(), epoch.getAsLong()));
        this.admission =
                new GlAccountingAuthority.Admission(
                        original.actor(),
                        original.approval(),
                        until,
                        original.intent(),
                        original.accountIds());
        requireCurrent();
    }

    public GlAccountingAuthority.Admission admission() {
        return admission;
    }

    public Duration remaining() {
        return window.remainingAt(nanos.getAsLong(), epoch.getAsLong());
    }

    public <T> reactor.core.publisher.Mono<T> bound(reactor.core.publisher.Mono<T> action) {
        return reactor.core.publisher.Mono.defer(() -> action.timeout(remaining()));
    }

    public void requireCurrent() {
        GlAccountingAuthority.requireCurrent(admission.actor());
        if (admission.validUntilEpochMillis() <= epoch.getAsLong())
            throw new IllegalStateException("GL native admission expired");
        remaining();
    }
}
