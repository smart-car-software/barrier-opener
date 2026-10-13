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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public class StartupPromptResolverTest {

    private static Barrier barrier(String name, Barrier.ActionType type) {
        Barrier b = new Barrier();
        b.setName(name);
        b.setActionType(type);
        b.setDetectionRadius(50);
        return b;
    }

    @Test
    public void nothingInZone_asksNothing() {
        assertNull(StartupPromptResolver.candidate(new ArrayList<>()));
        assertNull(StartupPromptResolver.candidate(null));
    }

    @Test
    public void offersTheNearestBarrier() {
        Barrier nearest = barrier("gate", Barrier.ActionType.WEBHOOK);
        Barrier farther = barrier("second gate", Barrier.ActionType.WEBHOOK);
        assertSame(nearest, StartupPromptResolver.candidate(Arrays.asList(nearest, farther)));
    }

    @Test
    public void neverSkipsAheadToAFartherBarrier() {
        // The webhook gate is the one the car is parked at; offering the neighbours' GSM barrier
        // instead because it happens to need no network would be the wrong gate entirely.
        Barrier nearestWebhook = barrier("home gate", Barrier.ActionType.WEBHOOK);
        Barrier fartherCall = barrier("neighbour barrier", Barrier.ActionType.CALL);
        assertSame(nearestWebhook,
                StartupPromptResolver.candidate(Arrays.asList(nearestWebhook, fartherCall)));
    }

    @Test
    public void onlyRequestBarriersWaitForTheirHost() {
        // Dialling a GSM relay needs no network at all, so it is offered the moment GPS lands.
        assertTrue(StartupPromptResolver.needsReachableHost(
                barrier("gate", Barrier.ActionType.WEBHOOK)));
        assertTrue(StartupPromptResolver.needsReachableHost(
                barrier("gate", Barrier.ActionType.GATE_API)));
        assertFalse(StartupPromptResolver.needsReachableHost(
                barrier("shlagbaum", Barrier.ActionType.CALL)));
        assertFalse(StartupPromptResolver.needsReachableHost(null));
    }

    private static final Barrier BOTH_WAITS = waits(true, true);

    private static Barrier waits(boolean belt, boolean warmUp) {
        Barrier b = barrier("gate", Barrier.ActionType.WEBHOOK);
        b.setStartupWaitForBelt(belt);
        b.setStartupWaitForWarmUp(warmUp);
        return b;
    }

    private static StartupPromptResolver.CarGate gate(boolean answered, Boolean inPark,
                                                     Boolean buckled, Float rpm) {
        return StartupPromptResolver.carGate(BOTH_WAITS, answered, inPark, buckled, rpm);
    }

    @Test
    public void bothWaitsOff_asksAtOnce() {
        // The default for every existing barrier: not even the car API is waited for.
        Barrier none = waits(false, false);
        assertEquals(StartupPromptResolver.CarGate.READY,
                StartupPromptResolver.carGate(none, false, null, null, null));
        assertEquals(StartupPromptResolver.CarGate.READY,
                StartupPromptResolver.carGate(none, true, true, false, 1400f));
    }

    @Test
    public void beltOnly_ignoresAColdEngine() {
        Barrier belt = waits(true, false);
        assertEquals(StartupPromptResolver.CarGate.READY,
                StartupPromptResolver.carGate(belt, true, true, true, 1400f));
        assertEquals(StartupPromptResolver.CarGate.WAITING_FOR_DRIVER,
                StartupPromptResolver.carGate(belt, true, true, false, 750f));
    }

    @Test
    public void warmUpOnly_ignoresTheBelt() {
        Barrier warmUp = waits(false, true);
        assertEquals(StartupPromptResolver.CarGate.READY,
                StartupPromptResolver.carGate(warmUp, true, true, false, 750f));
        assertEquals(StartupPromptResolver.CarGate.WARMING_UP,
                StartupPromptResolver.carGate(warmUp, true, true, false, 1400f));
    }

    @Test
    public void waitsForTheCarApiBeforeJudgingTheCar() {
        // Before the first connect comes back, "no reading" means "not yet", not "no sensor".
        assertEquals(StartupPromptResolver.CarGate.WAIT_FOR_CAR_API, gate(false, null, null, null));
    }

    @Test
    public void unbuckledDriver_waitsForTheDriver() {
        // A remote start: engine running, nobody in the seat. Warm or not, nobody would see a popup.
        assertEquals(StartupPromptResolver.CarGate.WAITING_FOR_DRIVER, gate(true, true, false, 750f));
        assertEquals(StartupPromptResolver.CarGate.WAITING_FOR_DRIVER, gate(true, true, false, 1400f));
        assertEquals(StartupPromptResolver.CarGate.WAITING_FOR_DRIVER, gate(true, null, false, null));
    }

    @Test
    public void buckledOnFastIdle_isWarmingUp() {
        assertEquals(StartupPromptResolver.CarGate.WARMING_UP, gate(true, true, true, 1350f));
        assertEquals(StartupPromptResolver.CarGate.WARMING_UP, gate(true, true, true, 1000f));
    }

    @Test
    public void buckledAndWarm_isReady() {
        assertEquals(StartupPromptResolver.CarGate.READY, gate(true, true, true, 999f));
        assertEquals(StartupPromptResolver.CarGate.READY, gate(true, true, true, 750f));
    }

    @Test
    public void shiftingOutOfPark_overridesBeltAndColdEngine() {
        // The driver is setting off now — and a firmware whose belt reads "unbuckled" forever
        // must still get its question at the latest here.
        assertEquals(StartupPromptResolver.CarGate.READY, gate(true, false, false, 1400f));
    }

    @Test
    public void unknownGear_stillWaitsForTheRpm() {
        assertEquals(StartupPromptResolver.CarGate.WARMING_UP, gate(true, null, true, 1400f));
    }

    @Test
    public void unreadableSensors_behaveAsBefore() {
        // A unit without the sensors must keep offering the gate rather than never asking.
        assertEquals(StartupPromptResolver.CarGate.READY, gate(true, true, null, null));
        assertEquals(StartupPromptResolver.CarGate.WARMING_UP, gate(true, true, null, 1400f));
        assertEquals(StartupPromptResolver.CarGate.READY, gate(true, true, null, 800f));
    }
}
