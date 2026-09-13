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

import java.util.List;

/**
 * Pure (Android-free, JVM-testable) decision for the one prompt the app offers right after it
 * starts: "you are standing inside a barrier's zone — open it?".
 *
 * <p>This is the just-started-the-car case, and it is deliberately not the same question the
 * driving detection asks. A parked car produces no bearing, so direction can't be judged, and its
 * barrier is usually still suppressed by {@link TriggerStateStore} from the drive in — both of
 * which would keep the normal path silent. Here only "am I inside the zone" matters.
 *
 * <p>Being inside the zone is decided by {@link NearbyResolver}; this class only picks which of
 * those barriers to offer, and says whether offering it has to wait — for a reachable endpoint,
 * or, where the barrier asks for it, for a buckled-in driver and a warmed-up engine.
 */
public final class StartupPromptResolver {

    private StartupPromptResolver() {}

    /**
     * @param inZoneNearestFirst barriers whose zone the car is inside, sorted nearest-first
     *                           ({@link NearbyResolver.Resolution#inZone})
     * @return the barrier to offer, or null when the car is in no zone at all
     *
     * <p>Only the nearest barrier is ever a candidate: it is the one the car is parked at. Never a
     * farther one just because it happens to be openable right now — that would open the wrong
     * gate.
     */
    public static Barrier candidate(List<Barrier> inZoneNearestFirst) {
        if (inZoneNearestFirst == null || inZoneNearestFirst.isEmpty()) {
            return null;
        }
        return inZoneNearestFirst.get(0);
    }

    /**
     * Whether offering this barrier means nothing until its endpoint answers. A GSM barrier is
     * dialled and needs no network; a webhook one is a request, and at the moment the engine
     * starts the home network is typically seconds away from being up — so the caller waits for
     * the host rather than putting a question to the driver that "Yes" cannot fulfil.
     */
    public static boolean needsReachableHost(Barrier barrier) {
        return barrier != null && barrier.getActionType() == Barrier.ActionType.WEBHOOK;
    }

    /** Above this the engine is still on its cold-start fast idle; a warm one idles well below. */
    public static final float WARM_IDLE_RPM = 1000f;

    /** What the car says about putting the startup question now. */
    public enum CarGate {
        /** The car API hasn't answered yet, so "no reading" can't be told from "no sensor". */
        WAIT_FOR_CAR_API,
        /** Nobody buckled into the driver's seat — likely a remote start warming an empty car. */
        WAITING_FOR_DRIVER,
        /** Parked with the engine on fast idle: the driver is warming the car, not leaving yet. */
        WARMING_UP,
        READY
    }

    /**
     * Holds the startup question, for the barrier that asks for it, until someone is about to drive
     * off. Two things can say "not yet", each behind its own switch on the barrier: an unbuckled
     * driver's belt — an engine started remotely warms a car nobody sits in, and a popup there
     * times out unseen, spending the one question — and an engine still on fast idle, since a gate
     * opened at the first fix would stand open for the minutes it takes to settle.
     *
     * @param barrier        the barrier about to be offered; its switches decide what is waited for
     * @param carApiAnswered whether the car API's first connection attempt has come back
     * @param inPark         true in Park, false in another gear, null when the gear is unknown
     * @param driverBuckled  true buckled, false unbuckled, null when the belt can't be read
     * @param rpm            engine speed, null when it can't be read
     *
     * <p>A gear out of Park overrides both: the driver has shifted to drive off, belt or no belt,
     * cold engine or not, and a gate that opens only once they are already waiting at it is no
     * help. It is also the way out if a firmware reports an unbuckled belt forever. And a reading
     * that isn't there lets the question through — a unit without the sensor behaves as it always
     * did rather than never asking.
     */
    public static CarGate carGate(Barrier barrier, boolean carApiAnswered, Boolean inPark,
                                  Boolean driverBuckled, Float rpm) {
        boolean waitForBelt = barrier != null && barrier.isStartupWaitForBelt();
        boolean waitForWarmUp = barrier != null && barrier.isStartupWaitForWarmUp();
        if (!waitForBelt && !waitForWarmUp) {
            return CarGate.READY; // Nothing to wait for, so not even for the car API.
        }
        if (!carApiAnswered) {
            return CarGate.WAIT_FOR_CAR_API;
        }
        if (Boolean.FALSE.equals(inPark)) {
            return CarGate.READY;
        }
        if (waitForBelt && Boolean.FALSE.equals(driverBuckled)) {
            return CarGate.WAITING_FOR_DRIVER;
        }
        if (waitForWarmUp && rpm != null && rpm >= WARM_IDLE_RPM) {
            return CarGate.WARMING_UP;
        }
        return CarGate.READY;
    }
}
