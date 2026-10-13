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

import java.util.ArrayList;
import java.util.List;

/**
 * Pure (Android-free, JVM-testable) logic that, for a given location, splits the barriers into:
 * <ul>
 *   <li>{@code inZone} — every barrier the car is physically inside the radius of, regardless of
 *       direction, sorted nearest-first. These are all reachable from the popup's "also in range"
 *       list so the driver can open, say, the entrance barrier even while pointed at the exit.</li>
 *   <li>{@code freshMatches} — barriers that match by direction <i>and</i> aren't already triggered.
 *       These get suppressed (marked triggered) so the popup doesn't stack.</li>
 *   <li>{@code primary} — the closest fresh directional match; the one the popup suggests. Null when
 *       there is nothing new to offer.</li>
 * </ul>
 */
public final class NearbyResolver {

    public interface TriggeredPredicate {
        boolean isTriggered(long barrierId);
    }

    /** Whether the car drove into this barrier's zone from outside; see {@link ApproachTracker}. */
    public interface ApproachedPredicate {
        boolean hasApproached(long barrierId);
    }

    public static final class Resolution {
        public final Barrier primary;
        public final List<Barrier> freshMatches;
        public final List<Barrier> inZone;

        Resolution(Barrier primary, List<Barrier> freshMatches, List<Barrier> inZone) {
            this.primary = primary;
            this.freshMatches = freshMatches;
            this.inZone = inZone;
        }
    }

    private NearbyResolver() {}

    /** For callers with no gear to go by; the car is then treated as possibly driving. */
    public static Resolution resolve(List<Barrier> barriers, BarrierDetector.Fix fix,
                                     TriggeredPredicate triggered) {
        return resolve(barriers, fix, triggered, false);
    }

    /**
     * @param parked true only when the car is <i>known</i> to be in Park — an unreadable gear, or a
     *               unit with no car API, passes false and behaves as it always did.
     *
     * <p>A car in Park is going nowhere, so it cannot be approaching a gate, and nothing it is
     * standing inside is a directional match. The fix says otherwise often enough to matter: a
     * head unit's GPS comes up a good while after the engine does, and its first fixes carry a
     * bearing made of noise and a speed of zero — which for a bidirectional barrier is enough to
     * look exactly like a car driving in. The result was the gate being offered to a driver who
     * had not yet done up their belt. The in-zone list is still filled: the startup question is
     * built from it, and that one has its own idea of when the driver is ready.
     */
    public static Resolution resolve(List<Barrier> barriers, BarrierDetector.Fix fix,
                                     TriggeredPredicate triggered, boolean parked) {
        return resolve(barriers, fix, triggered, id -> true, parked);
    }

    /**
     * @param approached true only for barriers whose zone the car has <i>entered</i> from outside
     *                   and not left since. Being inside is not enough for a directional match: a
     *                   car standing inside the zone gets a bearing with every fix, and one of them
     *                   will point at the gate eventually. Callers without a fix history pass
     *                   {@code id -> true}; the in-zone list ignores it either way.
     */
    public static Resolution resolve(List<Barrier> barriers, BarrierDetector.Fix fix,
                                     TriggeredPredicate triggered, ApproachedPredicate approached,
                                     boolean parked) {
        List<Barrier> inZone = new ArrayList<>();
        List<Barrier> freshMatches = new ArrayList<>();

        for (Barrier barrier : barriers) {
            BarrierDetector.Result r = BarrierDetector.evaluate(barrier, fix);
            if (r == BarrierDetector.Result.OUT_OF_RANGE) {
                continue;
            }
            inZone.add(barrier);
            if (!parked && r == BarrierDetector.Result.IN_RANGE
                    && approached.hasApproached(barrier.getId())
                    && !triggered.isTriggered(barrier.getId())) {
                freshMatches.add(barrier);
            }
        }

        inZone.sort((a, b) -> Double.compare(distance(fix, a), distance(fix, b)));

        Barrier primary = null;
        double best = Double.MAX_VALUE;
        for (Barrier barrier : freshMatches) {
            double d = distance(fix, barrier);
            if (d < best) {
                best = d;
                primary = barrier;
            }
        }

        return new Resolution(primary, freshMatches, inZone);
    }

    private static double distance(BarrierDetector.Fix fix, Barrier barrier) {
        return GeoMath.distanceMeters(
                fix.latitude, fix.longitude,
                barrier.getLatitude(), barrier.getLongitude());
    }
}
