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
 * Pure (Android-free, JVM-testable) decision for "should we offer to close this barrier now?".
 *
 * A barrier becomes a candidate only after the driver actually confirmed opening it, and only if
 * it is a webhook barrier with at least one closing trigger enabled. The two triggers are
 * independent and whichever fires first wins:
 *
 * <ul>
 *   <li><b>Departure</b> — the car has left the detection zone plus {@link #DEPARTURE_MARGIN_M}.
 *       The margin is hysteresis, not distance: it only has to outlast GPS jitter around the edge
 *       of the zone, so the question comes as soon as the car is actually out rather than a block
 *       later.</li>
 *   <li><b>Timer</b> — the configured number of minutes has passed since opening, which covers the
 *       case where the car never drives away (parked inside) or GPS never reports the departure.</li>
 * </ul>
 */
public final class CloseResolver {

    /**
     * How far past the detection radius the car must be before departure counts. Small on purpose —
     * the driver should be asked right after leaving, not a block away — but non-zero so a fix
     * wobbling around the boundary doesn't fire the prompt while the car is still at the gate.
     */
    public static final double DEPARTURE_MARGIN_M = 5.0;

    public enum Reason {
        /** The car drove away from the barrier. */
        DEPARTURE,
        /** The configured delay elapsed. */
        TIMER
    }

    private CloseResolver() {}

    /**
     * @param openedAtMs when the driver confirmed opening
     * @param nowMs      current time
     * @param distanceM  current distance to the barrier, or a negative value when unknown (no fix)
     * @return the trigger that fired, or null when it is not time to ask yet
     */
    public static Reason evaluate(Barrier barrier, long openedAtMs, long nowMs, double distanceM) {
        if (barrier == null || !barrier.isClosable()) {
            return null;
        }
        if (barrier.isCloseOnDeparture() && distanceM >= 0
                && distanceM > barrier.getDetectionRadius() + DEPARTURE_MARGIN_M) {
            return Reason.DEPARTURE;
        }
        if (barrier.isCloseOnTimer()) {
            long elapsed = nowMs - openedAtMs;
            if (elapsed >= barrier.getCloseDelayMinutes() * 60_000L) {
                return Reason.TIMER;
            }
        }
        return null;
    }
}
