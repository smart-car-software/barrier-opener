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

/**
 * What the state indicator shows for a gate: the last known state, and whether it can still be
 * believed. Pure, so the staleness rule is tested rather than eyeballed in a car.
 *
 * <p>Two clocks decide "stale", and neither is the head unit's wall clock — a car's clock drifts
 * by minutes, so comparing the server's {@code updated} against it would flag live data as old:
 * <ul>
 *   <li>the service's own {@code age_seconds} since it last heard from Home Assistant, plus its
 *       explicit {@code ha=unreachable};</li>
 *   <li>a local monotonic timer since the last GET that succeeded, which catches the link between
 *       the car and the service dropping while the service itself is fine.</li>
 * </ul>
 */
public final class GateIndicator {

    /** How old a reading may be, on either clock, before it is shown as stale. */
    public static final long STALE_AFTER_MS = 10_000L;

    private GateIndicator() {}

    /** What to render: the state to name, and whether to mute it as out of date. */
    public static final class Reading {
        public final GateState.State state;
        public final boolean stale;

        Reading(GateState.State state, boolean stale) {
            this.state = state;
            this.stale = stale;
        }
    }

    /**
     * @param last         the last successfully fetched state, or null when none has arrived yet
     * @param receivedAtMs monotonic time ({@code SystemClock.elapsedRealtime()}) {@code last} arrived
     * @param nowMs        the same clock, now
     */
    public static Reading describe(GateState last, long receivedAtMs, long nowMs) {
        if (last == null) {
            return new Reading(GateState.State.UNKNOWN, true);
        }
        boolean stale = !last.haReachable
                || (last.ageSeconds != null && last.ageSeconds * 1000L > STALE_AFTER_MS)
                || nowMs - receivedAtMs > STALE_AFTER_MS;
        return new Reading(last.state, stale);
    }
}
