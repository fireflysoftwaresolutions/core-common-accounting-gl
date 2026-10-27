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
import java.util.concurrent.TimeUnit;

public final class GlNativeTimeWindow {
    private final long untilEpochMillis;
    private final long monotonicUntilNanos;

    private GlNativeTimeWindow(long untilEpochMillis, long monotonicUntilNanos) {
        this.untilEpochMillis = untilEpochMillis;
        this.monotonicUntilNanos = monotonicUntilNanos;
    }

    public static GlNativeTimeWindow from(GlNativeContextStamp stamp, long beforeNativeNanos) {
        if (stamp == null
                || stamp.recordedAtEpochMillis() <= 0
                || stamp.effectiveUntilEpochMillis() > GlPostingRules.MAX_TIME)
            throw new IllegalArgumentException("Actual bounded GL native timing required");
        long millis =
                Math.subtractExact(
                        stamp.effectiveUntilEpochMillis(), stamp.recordedAtEpochMillis());
        if (millis <= 0 || millis > 30000)
            throw new IllegalArgumentException("Finite GL native window required");
        millis -= 1;
        if (millis <= 0) throw new Expired();
        return new GlNativeTimeWindow(
                stamp.effectiveUntilEpochMillis(),
                beforeNativeNanos + TimeUnit.MILLISECONDS.toNanos(millis));
    }

    public long untilEpochMillis() {
        return untilEpochMillis;
    }

    public Duration remainingAt(long nanos, long epochMillis) {
        long monotonic = monotonicUntilNanos - nanos;
        long epoch = untilEpochMillis - epochMillis;
        if (monotonic <= 0 || epoch <= 0) throw new Expired();
        return Duration.ofNanos(
                Math.min(monotonic, TimeUnit.MILLISECONDS.toNanos(Math.min(epoch, 30000))));
    }

    public long untilAt(long nanos, long epochMillis) {
        long millis = remainingAt(nanos, epochMillis).toMillis();
        if (millis <= 0) throw new Expired();
        return Math.min(untilEpochMillis, Math.addExact(epochMillis, millis));
    }

    public void requireCurrent() {
        remainingAt(System.nanoTime(), System.currentTimeMillis());
    }

    public static final class Expired extends IllegalStateException {
        private Expired() {
            super("GL native operation window expired");
        }
    }
}
