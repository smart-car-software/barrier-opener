/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.barrieropener.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

public class GateIndicatorTest {

    private static final Set<String> NONE = Collections.emptySet();

    private static GateState state(GateState.State s, Integer ageSeconds, boolean haReachable) {
        return new GateState(s, 0, "idle", false, NONE, ageSeconds, haReachable, null, null);
    }

    @Test
    public void nothingReceivedYet_isUnknownAndStale() {
        GateIndicator.Reading r = GateIndicator.describe(null, 0L, 5_000L);
        assertEquals(GateState.State.UNKNOWN, r.state);
        assertTrue(r.stale);
    }

    @Test
    public void freshReading_isShownAsIs() {
        GateIndicator.Reading r = GateIndicator.describe(
                state(GateState.State.CLOSED, 2, true), 100_000L, 101_500L);
        assertEquals(GateState.State.CLOSED, r.state);
        assertFalse(r.stale);
    }

    @Test
    public void serviceLostHomeAssistant_isStale() {
        // ha=unreachable: the service itself says its data is old, whatever our timer thinks.
        assertTrue(GateIndicator.describe(
                state(GateState.State.UNKNOWN, 0, false), 100_000L, 100_500L).stale);
        // age_seconds past the limit says the same thing even with ha still "ok".
        assertTrue(GateIndicator.describe(
                state(GateState.State.OPEN, 11, true), 100_000L, 100_500L).stale);
        assertFalse(GateIndicator.describe(
                state(GateState.State.OPEN, 10, true), 100_000L, 100_500L).stale);
    }

    @Test
    public void carLostTheService_isStaleByTheLocalClock() {
        // No GET has succeeded for a while: the last state is kept, but muted. This uses the
        // monotonic clock, never the server's timestamp against the head unit's drifting one.
        GateState last = state(GateState.State.CLOSED, 1, true);
        assertFalse(GateIndicator.describe(last, 100_000L, 100_000L + GateIndicator.STALE_AFTER_MS).stale);
        GateIndicator.Reading r = GateIndicator.describe(last, 100_000L,
                100_000L + GateIndicator.STALE_AFTER_MS + 1);
        assertTrue(r.stale);
        assertEquals(GateState.State.CLOSED, r.state); // Still names the last known state.
    }

    @Test
    public void missingAgeIsNotStaleByItself() {
        assertFalse(GateIndicator.describe(
                state(GateState.State.PARTIAL, null, true), 100_000L, 100_100L).stale);
    }
}
