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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;

class GlNativeTimeWindowTest {
    static Object call(Object target, String method, Class<?>[] types, Object... args) {
        try {
            return target.getClass().getMethod(method, types).invoke(target, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException failure) throw failure;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    static Object window(long now, long until, long anchor) {
        var type =
                assertDoesNotThrow(
                        () ->
                                Class.forName(
                                        "io.fireflysoftware.core.accounting.core.services.posting.GlNativeContextStamp"));
        var stamp =
                assertDoesNotThrow(
                        () -> type.getConstructor(long.class, long.class).newInstance(now, until));
        var helper =
                assertDoesNotThrow(
                        () ->
                                Class.forName(
                                        "io.fireflysoftware.core.accounting.core.services.posting.GlNativeTimeWindow"));
        try {
            return helper.getMethod("from", type, long.class).invoke(null, stamp, anchor);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException failure) throw failure;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    static Duration remaining(Object value, long nano, long epoch) {
        return (Duration)
                call(value, "remainingAt", new Class[] {long.class, long.class}, nano, epoch);
    }

    @Test
    void nativeHorizonIsRetainedWithOneMillisecondQuantum() {
        var w = window(100000, 100010, 1000000000);
        assertEquals(9, remaining(w, 1000000000, 100000).toMillis());
        assertEquals(100010L, call(w, "untilEpochMillis", new Class[] {}));
    }

    @Test
    void preNativeElapsedTimeIsNeverRenewed() {
        var w = window(100000, 100010, 1000000000);
        assertEquals(4, remaining(w, 1005000000, 100000).toMillis());
    }

    @Test
    void wallClockRollbackCannotRenewMonotonicBudget() {
        var w = window(100000, 100010, 1000000000);
        assertEquals(4, remaining(w, 1005000000, 99000).toMillis());
    }

    @Test
    void wallClockAdvanceCanOnlyNarrowBudget() {
        var w = window(100000, 100010, 1000000000);
        assertEquals(2, remaining(w, 1000000000, 100008).toMillis());
    }

    @Test
    void exhaustedFloorSliverIsRefused() {
        assertThrows(RuntimeException.class, () -> window(100000, 100001, 1000000000));
    }

    @Test
    void exactMonotonicBoundaryIsExpired() {
        var w = window(100000, 100010, 1000000000);
        assertThrows(RuntimeException.class, () -> remaining(w, 1009000000, 100000));
    }

    @Test
    void unsupportedNativeWindowIsNotConvertedOrExtended() {
        assertThrows(RuntimeException.class, () -> window(100000, 130001, 0));
        assertThrows(RuntimeException.class, () -> window(0, 10, 0));
    }

    @Test
    void bodyHandoffUsesRemainingBudgetRatherThanNewTtl() {
        var w = window(100000, 100010, 1000000000);
        assertEquals(
                100004L,
                call(w, "untilAt", new Class[] {long.class, long.class}, 1005000000L, 100000L));
    }

    @Test
    void supportedNanoTimeWrapIsComparedBySubtraction() {
        long anchor = Long.MAX_VALUE - 5000000;
        var w = window(100000, 100010, anchor);
        assertEquals(1, remaining(w, anchor + 8000000, 100000).toMillis());
    }
}
