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
 *   <li><b>Departure</b> — the car has been at the gate, inside the barrier's <i>closing</i>
 *       radius, and has since moved out past it plus {@link #DEPARTURE_MARGIN_M}. The closing
 *       radius is its own setting and is usually much smaller than the detection radius: opening
 *       wants distance, so a slow gate is open by the time the car arrives, while closing wants the
 *       car just through the gate. Requiring the car to have been inside first is what makes the
 *       small radius safe — the open question is put far out, and without that rule the car would
 *       count as "departed" the moment it said yes. The margin is hysteresis, not distance: it only
 *       has to outlast GPS jitter around the boundary.</li>
 *   <li><b>Timer</b> — the configured number of minutes has passed since opening <i>and</i> the car
 *       is still inside the zone. That is the case it exists for: the car drove in and stayed
 *       (parked inside), or GPS never reported the departure.</li>
 *   <li><b>Park</b> — the driver shifted into Park inside the zone, so the car has arrived and
 *       stopped. Unlike the other two this is an event rather than a state, so it arrives through
 *       {@link #evaluateOnPark} instead of the periodic {@link #evaluate} call.</li>
 * </ul>
 *
 * <p>Both of those are gated on being in the zone, and deliberately stay silent when there is no
 * fix at all: a question about a gate the car may be kilometres away from is worse than a gate
 * left open, and "away from the gate" is what the departure trigger is for.
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
        TIMER,
        /** The transmission was put into Park. */
        PARK
    }

    private CloseResolver() {}

    /**
     * The Park trigger: the gearbox says the journey is over. Time doesn't enter into it, but
     * distance does — parking two districts away says nothing about this gate.
     *
     * @param distanceM current distance to the barrier, or a negative value when unknown (no fix)
     * @return {@link Reason#PARK} when this barrier should be offered for closing now, else null
     */
    public static Reason evaluateOnPark(Barrier barrier, double distanceM) {
        if (barrier == null || !barrier.isClosable() || !barrier.isCloseOnPark()) {
            return null;
        }
        return isInZone(barrier, distanceM) ? Reason.PARK : null;
    }

    /** Inside the detection radius, and known to be: an unknown distance is not "inside". */
    private static boolean isInZone(Barrier barrier, double distanceM) {
        return distanceM >= 0 && distanceM <= barrier.getDetectionRadius();
    }

    /**
     * @param openedAtMs when the driver confirmed opening
     * @param nowMs      current time
     * @param distanceM  current distance to the barrier, or a negative value when unknown (no fix)
     * @param closestM   the nearest the car has been to the barrier since opening, or a negative
     *                   value when no fix has been seen since
     * @return the trigger that fired, or null when it is not time to ask yet
     */
    public static Reason evaluate(Barrier barrier, long openedAtMs, long nowMs, double distanceM,
                                  double closestM) {
        if (barrier == null || !barrier.isClosable()) {
            return null;
        }
        if (barrier.isCloseOnDeparture() && distanceM >= 0) {
            double closeRadius = barrier.getCloseRadiusMeters();
            boolean wasAtTheGate = closestM >= 0 && closestM <= closeRadius;
            if (wasAtTheGate && distanceM > closeRadius + DEPARTURE_MARGIN_M) {
                return Reason.DEPARTURE;
            }
        }
        if (barrier.isCloseOnTimer() && isInZone(barrier, distanceM)) {
            long elapsed = nowMs - openedAtMs;
            if (elapsed >= barrier.getCloseDelayMinutes() * 60_000L) {
                return Reason.TIMER;
            }
        }
        return null;
    }
}
