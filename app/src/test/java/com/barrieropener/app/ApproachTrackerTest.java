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
import java.util.List;

public class ApproachTrackerTest {

    private static final double LAT = 55.751244;
    private static final double LON = 37.618423;
    /** Metres per degree of latitude, near enough for moving a test car north and south. */
    private static final double M_PER_DEG_LAT = 111_195.0;

    private static Barrier gate(double radius) {
        Barrier b = new Barrier();
        b.setId(1);
        b.setLatitude(LAT);
        b.setLongitude(LON);
        b.setDetectionRadius(radius);
        return b;
    }

    /** Feeds a fix {@code meters} north of the gate. */
    private static void at(ApproachTracker t, List<Barrier> gates, double meters) {
        t.update(gates, LAT + meters / M_PER_DEG_LAT, LON);
    }

    @Test
    public void drivingInFromOutside_arms() {
        Barrier g = gate(50);
        List<Barrier> gates = Collections.singletonList(g);
        ApproachTracker t = new ApproachTracker();
        at(t, gates, 200);
        assertFalse(t.isArmed(1));
        at(t, gates, 70);  // in the margin ring: not in yet
        assertFalse(t.isArmed(1));
        at(t, gates, 45);
        assertTrue(t.isArmed(1));
        at(t, gates, 10);  // still inside, still armed
        assertTrue(t.isArmed(1));
    }

    @Test
    public void firstSeenInside_isNotAnApproach() {
        // The service came up with the car parked in the zone: nothing was driven into.
        List<Barrier> gates = Collections.singletonList(gate(50));
        ApproachTracker t = new ApproachTracker();
        at(t, gates, 30);
        assertFalse(t.isArmed(1));
        at(t, gates, 20);
        assertFalse(t.isArmed(1));
    }

    @Test
    public void jitterAcrossTheBoundary_doesNotArm() {
        // Parked at 48 m from a 50 m gate; fixes wander out to 58 m and back. The exit margin is
        // 12.5 m here, so the car never counts as having left — and never as having arrived.
        List<Barrier> gates = Collections.singletonList(gate(50));
        ApproachTracker t = new ApproachTracker();
        at(t, gates, 48);
        for (double d : new double[]{52, 58, 49, 61, 47, 55, 44}) {
            at(t, gates, d);
            assertFalse("fix at " + d, t.isArmed(1));
        }
    }

    @Test
    public void leavingPastTheMargin_thenReturning_armsAgain() {
        List<Barrier> gates = Collections.singletonList(gate(50));
        ApproachTracker t = new ApproachTracker();
        at(t, gates, 30);
        assertFalse(t.isArmed(1));
        at(t, gates, 63);  // past 50 + 12.5: out
        at(t, gates, 40);  // and back in: a real approach
        assertTrue(t.isArmed(1));
    }

    @Test
    public void disarm_holdsUntilTheCarLeavesAndReturns() {
        List<Barrier> gates = Collections.singletonList(gate(50));
        ApproachTracker t = new ApproachTracker();
        at(t, gates, 200);
        at(t, gates, 40);
        assertTrue(t.isArmed(1));
        t.disarm(1);
        at(t, gates, 20);
        assertFalse(t.isArmed(1));
        at(t, gates, 58);  // the margin ring (50..62.5 m) is not "out"
        at(t, gates, 30);
        assertFalse(t.isArmed(1));
        at(t, gates, 100);
        at(t, gates, 30);
        assertTrue(t.isArmed(1));
    }

    @Test
    public void exitMargin_hasAFloorAndGrowsWithTheRadius() {
        assertEquals(20.0 + 10.0, ApproachTracker.exitRadius(gate(20)), 1e-9);   // floor: 10 m
        assertEquals(50.0 + 12.5, ApproachTracker.exitRadius(gate(50)), 1e-9);   // 25 %
        assertEquals(200.0 + 50.0, ApproachTracker.exitRadius(gate(200)), 1e-9);
    }

    @Test
    public void deletedBarriers_areForgotten() {
        Barrier g = gate(50);
        ApproachTracker t = new ApproachTracker();
        at(t, Collections.singletonList(g), 200);
        at(t, Collections.singletonList(g), 40);
        assertTrue(t.isArmed(1));
        t.update(Collections.emptyList(), LAT, LON);
        assertFalse(t.isArmed(1));
    }
}
