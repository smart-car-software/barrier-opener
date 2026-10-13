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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a stream of fixes into the one event detection actually wants: the car <i>entered</i> a
 * barrier's zone from outside. Pure and Android-free, so the rule is tested rather than driven.
 *
 * <p>Being inside the radius is not the same thing. A car parked just inside the zone — at home,
 * say — is inside on every fix, and GPS gives every fix a bearing; sooner or later one of them
 * points at the gate and the old "inside and pointing at it" rule offered to open a gate nobody was
 * driving through. So a barrier is <b>armed</b> only by a fix inside the radius that follows a fix
 * outside it, and stays armed until the car leaves again. A car first seen inside — the service
 * started while parked in the zone — is inside but unarmed; the startup question is for that.
 *
 * <p>Leaving takes more than the radius: the car must get out past {@link #exitRadius(Barrier)}, a
 * margin that grows with the radius, before it counts as outside. That hysteresis is what keeps a
 * fix wobbling across the boundary from producing an endless series of "entries".
 */
public final class ApproachTracker {

    /** The exit margin never goes below this, whatever the radius: GPS jitter has a floor. */
    public static final double MIN_EXIT_MARGIN_M = 10.0;
    /** ...and grows with the radius, because a bigger zone is drawn where fixes are rougher. */
    public static final double EXIT_MARGIN_FRACTION = 0.25;

    private enum Side {
        /** Beyond the exit radius, or never nearer: the next fix inside is an entry. */
        OUTSIDE,
        /** Entered from outside and still here: a directional match may trigger. */
        INSIDE_ARMED,
        /** First seen inside, or triggered already: inside, but not an approach. */
        INSIDE_IDLE
    }

    private final Map<Long, Side> sides = new HashMap<>();

    /** Distance beyond which the car counts as having left this barrier's zone. */
    public static double exitRadius(Barrier barrier) {
        double r = barrier.getDetectionRadius();
        return r + Math.max(MIN_EXIT_MARGIN_M, r * EXIT_MARGIN_FRACTION);
    }

    /** Feeds one fix; afterwards {@link #isArmed} answers for every barrier in the list. */
    public void update(List<Barrier> barriers, double latitude, double longitude) {
        Set<Long> seen = new HashSet<>();
        for (Barrier b : barriers) {
            long id = b.getId();
            seen.add(id);
            double d = GeoMath.distanceMeters(latitude, longitude, b.getLatitude(), b.getLongitude());
            Side side = sides.get(id);
            if (side == null) {
                // First sighting: standing inside is not an entry.
                sides.put(id, d <= b.getDetectionRadius() ? Side.INSIDE_IDLE : Side.OUTSIDE);
            } else if (d > exitRadius(b)) {
                sides.put(id, Side.OUTSIDE);
            } else if (d <= b.getDetectionRadius() && side == Side.OUTSIDE) {
                sides.put(id, Side.INSIDE_ARMED);
            }
            // Otherwise: in the margin ring, or already inside — nothing changes.
        }
        sides.keySet().retainAll(seen); // Deleted barriers don't keep a side.
    }

    /** True when the car has driven into this barrier's zone and not left it since. */
    public boolean isArmed(long barrierId) {
        return sides.get(barrierId) == Side.INSIDE_ARMED;
    }

    /** The approach has been acted on; it will not count again until the car leaves and returns. */
    public void disarm(long barrierId) {
        if (sides.get(barrierId) == Side.INSIDE_ARMED) {
            sides.put(barrierId, Side.INSIDE_IDLE);
        }
    }
}
